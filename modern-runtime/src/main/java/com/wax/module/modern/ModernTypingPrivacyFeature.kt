package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap

/**
 * API 102 port of the legacy TypingPrivacy feature.
 *
 * The legacy version hooks WhatsApp's composing-state broadcast, reads the
 * state type and the recipient JID, and suppresses the state when the user
 * asked for typing (or recording) privacy — globally, or per contact through
 * the custom-privacy map.
 *
 * Targets are derived from repository evidence: the method using
 * `HandleMeComposing/sendComposing`, additionally requiring the observed
 * three-parameter shape whose third parameter is an `int`. Zero or several
 * matches, or a different signature, disables only this feature.
 *
 * Privacy rules come from the Manager, which owns them, through a new
 * UID-authenticated read that answers **one contact at a time** and returns
 * only two booleans. Bulk-syncing every contact number into RemotePreferences
 * would move the whole address book into the injected process for no reason;
 * asking about the number the hook is already looking at exposes nothing new
 * and keeps the transferred data minimal. Answers are cached briefly because
 * the hook runs on a WhatsApp thread and must not block on IPC.
 *
 * Opt-in: needs either the global ghost-mode switch or the typing/recording
 * flags, and nothing happens without them.
 */
object ModernTypingPrivacyFeature {
    const val FEATURE_ID = "typing_privacy"
    const val ANCHOR_COMPOSING = "HandleMeComposing/sendComposing"
    const val PREF_GHOSTMODE = "ghostmode"
    const val PREF_GHOSTMODE_TYPING = "ghostmode_t"
    const val PREF_GHOSTMODE_RECORDING = "ghostmode_r"
    const val STATE_TYPING = 0
    const val STATE_RECORDING = 1
    private const val TAG = "WA-X TypingPrivacy102"

    enum class Outcome {
        DISABLED,
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
        ERROR,
    }

    /**
     * The per-behaviour state, which is not the same as the hook state (#450).
     *
     * Typing and recording are separate promises to the people you are talking
     * to, so they are reported separately even though one hook serves both:
     * a user who hides recording but not typing has to be able to see which one
     * is actually in force.
     */
    data class BehaviourState(
        val typingRequested: Boolean,
        val recordingRequested: Boolean,
        val typingWithheld: Boolean,
        val recordingWithheld: Boolean,
        val outcome: Outcome,
    ) {
        /** True only when the hook is installed and the behaviour is in force. */
        fun isBehaviourActive(
            requested: Boolean,
            withheld: Boolean,
        ): Boolean = outcome == Outcome.INSTALLED && requested && withheld
    }

    /**
     * Reads the three legacy switches into the two behaviours they control.
     *
     * `ghostmode` is the legacy global that covers both, so it counts towards
     * each behaviour rather than being a third thing.
     */
    @JvmStatic
    fun behaviourState(
        preferences: SharedPreferences,
        outcome: Outcome,
    ): BehaviourState {
        val global = preferences.getBoolean(PREF_GHOSTMODE, false)
        val typing = global || preferences.getBoolean(PREF_GHOSTMODE_TYPING, false)
        val recording = global || preferences.getBoolean(PREF_GHOSTMODE_RECORDING, false)
        // Installed is the ceiling: without it nothing is withheld, and
        // reporting the preference alone is exactly the false claim #450 rules
        // out.
        val installed = outcome == Outcome.INSTALLED
        return BehaviourState(
            typingRequested = typing,
            recordingRequested = recording,
            typingWithheld = typing && installed,
            recordingWithheld = recording && installed,
            outcome = outcome,
        )
    }

    data class PrivacyRule(
        val hideTyping: Boolean,
        val hideRecording: Boolean,
    )

    private val ruleCache = ConcurrentHashMap<String, PrivacyRule>()

    /** Pure decision, so the rule combination is testable without WhatsApp. */
    @JvmStatic
    fun shouldSuppress(
        stateType: Int,
        hideTyping: Boolean,
        hideRecording: Boolean,
    ): Boolean =
        (stateType == STATE_RECORDING && hideRecording) ||
            (stateType == STATE_TYPING && hideTyping)

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
        jidAccess: ModernJidAccess?,
    ): Outcome {
        val globalGhost = preferences.getBoolean(PREF_GHOSTMODE, false)
        val hideTypingGlobal = globalGhost || preferences.getBoolean(PREF_GHOSTMODE_TYPING, false)
        val hideRecordingGlobal = globalGhost || preferences.getBoolean(PREF_GHOSTMODE_RECORDING, false)
        if (!hideTypingGlobal && !hideRecordingGlobal) return Outcome.DISABLED
        if (jidAccess == null) return Outcome.UNSAFE_SIGNATURE

        val composing =
            try {
                DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                    dex.findMethod {
                        matcher {
                            addUsingString(ANCHOR_COMPOSING, StringMatchType.Contains)
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Composing resolver unavailable", failure)
                return Outcome.RESOLVER_MISSING
            }
        when {
            composing.isEmpty() -> return Outcome.RESOLVER_MISSING
            composing.size != 1 -> return Outcome.RESOLVER_AMBIGUOUS
        }
        val method =
            try {
                composing[0].getMethodInstance(target.classLoader)
            } catch (failure: Throwable) {
                Log.w(TAG, "Composing method unresolvable", failure)
                return Outcome.RESOLVER_MISSING
            }
        // The legacy resolver requires the state Int in slot 2.
        // Reject multiple Int slots instead of guessing which one represents composing.
        if (!isSafeComposingSignature(method.parameterTypes)) {
            return Outcome.UNSAFE_SIGNATURE
        }
        val jidClass = jidAccess.jidClass

        try {
            hooks.installFeature(
                FEATURE_ID,
                listOf(
                    ModernHookRegistry.Registration("typing_privacy.composing") {
                        val handle =
                            ModernHookBridge(framework).intercept(
                                method,
                                "wax.modern.typing_privacy.composing",
                            ) { chain ->
                                val stateType = composingState(method.parameterTypes, chain.args.toList())
                                val jid =
                                    chain.args.firstOrNull { candidate ->
                                        candidate != null && jidClass.isInstance(candidate)
                                    }
                                val number = jidAccess.phoneNumber(jid)
                                val rule = number?.let { lookup(target, it, chain.args) }
                                val hideTyping = hideTypingGlobal || (rule?.hideTyping == true)
                                val hideRecording =
                                    hideRecordingGlobal || (rule?.hideRecording == true)
                                if (stateType != null && shouldSuppress(stateType, hideTyping, hideRecording)) {
                                    // Legacy semantics: the state callback never fires.
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                        ModernHookRegistry.Handle { handle.unhook() }
                    },
                ),
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Typing privacy hook unavailable", failure)
            return Outcome.ERROR
        }
        return Outcome.INSTALLED
    }

    /** Fail closed when the composing-state signature is missing or ambiguous. */
    internal fun isSafeComposingSignature(parameterTypes: Array<Class<*>>): Boolean =
        parameterTypes.size >= 3 &&
            parameterTypes[2] == Int::class.javaPrimitiveType &&
            parameterTypes.count { it == Int::class.javaPrimitiveType } == 1

    /** Read the observed composing state from the third argument, not the first. */
    internal fun composingState(parameterTypes: Array<Class<*>>, args: List<Any?>): Int? =
        if (isSafeComposingSignature(parameterTypes) && args.size == parameterTypes.size) {
            args[2] as? Int
        } else {
            null
        }

    /**
     * Cached per-contact rule lookup. A cache hit answers immediately; a miss
     * answers with the global-only rule and asks the Manager in the
     * background, so a hook thread never blocks on IPC.
     */
    private fun lookup(
        context: Context,
        number: String,
        args: List<Any?>,
    ): PrivacyRule {
        ruleCache[number]?.let { return it }
        val contextRef = context.applicationContext
        val packageName = context.packageName
        Worker.execute {
            val fetched = ModernPrivacyRulesClient.fetch(contextRef, packageName, number)
            if (fetched != null) ruleCache[number] = fetched
        }
        return PrivacyRule(false, false)
    }

    /** Exposed for tests and for a manual refresh after a settings change. */
    @JvmStatic
    fun clearRuleCache() {
        ruleCache.clear()
    }

    private object Worker {
        private val executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "wax-api102-privacy-rules").apply { isDaemon = true }
            }

        fun execute(block: () -> Unit) {
            try {
                executor.execute(block)
            } catch (rejected: RuntimeException) {
                Log.w(TAG, "Privacy rule worker unavailable", rejected)
            }
        }
    }
}
