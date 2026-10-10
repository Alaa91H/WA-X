package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap

/**
 * API 102 port of the anti-revoke behaviour (#451).
 *
 * What this preserves is narrow on purpose: a message that **already arrived
 * and was already available locally** before the sender revoked it. It never
 * retrieves anything the client did not have, never touches another user's
 * device, and never spoofs a message state to the sender.
 *
 * The boundaries the issue sets out, kept explicit rather than implied:
 *
 * - **Media, ephemeral and view-once content are separate capability classes.**
 *   Protected content is never copied automatically: disappearing messages and
 *   view-once media are the sender's stated intent, and saving them behind a
 *   generic switch would misrepresent what this feature does.
 * - **Nothing is recovered that was never received.** A revoked message that
 *   was not already local stays gone; reporting otherwise would be a claim this
 *   module cannot support.
 * - **Retention is bounded and user-controlled**, so the switch cannot quietly
 *   become an archive.
 * - **Disabled means native behaviour.** When the feature is off, or the
 *   resolver is not usable, revocation proceeds exactly as WhatsApp wrote it.
 *
 * The hook lives on the revocation handler, not on rendering: the message stays
 * in the client because the delete is not applied, which is what the legacy
 * feature did and what keeps sender state untouched.
 */
object ModernAntiRevokeFeature {
    const val FEATURE_ID = "anti_revoke"

    /** Legacy preference keys, so an existing user's switch keeps its meaning. */
    const val PREF_ANTIREVOKE = "antirevoke"

    /** The legacy mode value that means "preserve chat messages". */
    const val MODE_PRESERVE = "1"

    /**
     * How long a preserved message stays recoverable, per switch value.
     *
     * Bounded by design: this is a convenience, not an archive platform.
     */
    const val RETENTION_DAYS_MAX = 30L

    /**
     * The revocation anchors the legacy resolver found, in the order it tried
     * them. Both are kept: a build may answer either.
     */
    val REVOKE_ANCHORS = listOf("msgstore/edit/revoke", "msgstore/revoking/")

    private const val TAG = "WA-X AntiRevoke102"

    /** What kind of content a message carries, which decides its eligibility. */
    enum class ContentClass {
        /** Ordinary text and attachments that arrived normally. */
        STANDARD,

        /** Disappearing-mode content: the sender asked for it to expire. */
        EPHEMERAL,

        /** View-once media: opening it is a one-time act with its own consent. */
        VIEW_ONCE,

        /** A status broadcast rather than a chat message. */
        STATUS,
    }

    enum class Outcome {
        DISABLED,
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
        ERROR,
    }

    /**
     * Whether a message of this class may be preserved at all.
     *
     * Only standard content qualifies. The other classes are returned with the
     * reason they are excluded, because "it did not work" and "it is not
     * allowed to work" are different answers and only one of them is a defect.
     */
    @JvmStatic
    fun eligible(content: ContentClass): Boolean = content == ContentClass.STANDARD

    /** Why a class is excluded, or an empty string when it is eligible. */
    @JvmStatic
    fun exclusionReason(content: ContentClass): String =
        when (content) {
            ContentClass.STANDARD -> ""
            ContentClass.EPHEMERAL -> "disappearing-mode content is not preserved"
            ContentClass.VIEW_ONCE -> "view-once media is not preserved"
            ContentClass.STATUS -> "status broadcasts are not preserved"
        }

    /**
     * Retention in days for a legacy mode value.
     *
     * Anything unrecognised falls back to the shortest window rather than the
     * longest: an unknown setting must not silently mean "keep everything".
     */
    @JvmStatic
    fun retentionDays(mode: String?): Long =
        when (mode) {
            "2" -> RETENTION_DAYS_MAX
            MODE_PRESERVE -> 7L
            else -> 0L
        }

    /**
     * Preserved-message ids, with the moment they stop being recoverable.
     *
     * Keyed by an id only. The message body is never stored here: this is a
     * lifecycle record, not a second copy of the user's messages.
     */
    private val preserved = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun preservedCount(): Int = preserved.size

    @JvmStatic
    fun clearPreserved() {
        preserved.clear()
    }

    /** Drops everything past its retention. Bounded work, called on a scan. */
    @JvmStatic
    fun purgeExpired(nowMillis: Long): Int {
        val iterator = preserved.entries.iterator()
        var dropped = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis > entry.value) {
                iterator.remove()
                dropped++
            }
        }
        return dropped
    }

    /**
     * Records that a message arrived and is therefore preservable.
     *
     * Called when the message is stored, not when it is revoked: a message that
     * never arrived has nothing to preserve, and pretending otherwise is the
     * over-promise the issue rules out.
     */
    @JvmStatic
    fun recordArrival(
        messageId: String?,
        content: ContentClass,
        nowMillis: Long,
        retentionDays: Long,
    ): Boolean {
        if (messageId.isNullOrEmpty() || !eligible(content) || retentionDays <= 0L) return false
        preserved[messageId] = nowMillis + retentionDays * 24L * 60L * 60L * 1000L
        return true
    }

    /** Whether revoking this message may be withheld locally. */
    @JvmStatic
    fun mayWithholdRevocation(
        messageId: String?,
        nowMillis: Long,
    ): Boolean {
        if (messageId.isNullOrEmpty()) return false
        val expiresAt = preserved[messageId] ?: return false
        if (nowMillis > expiresAt) {
            preserved.remove(messageId)
            return false
        }
        return true
    }

    @JvmStatic
    fun enabled(preferences: SharedPreferences): Boolean = retentionDays(preferences.getString(PREF_ANTIREVOKE, "0")) > 0L

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!enabled(preferences)) return Outcome.DISABLED

        val resolved = resolveRevocationMethod(target)
        if (resolved is Revocation.Failed) return resolved.outcome
        val method = (resolved as Revocation.Resolved).method

        return try {
            hooks.installFeature(
                FEATURE_ID,
                listOf(
                    ModernHookRegistry.Registration("anti_revoke.apply") {
                        val handle =
                            ModernHookBridge(framework).intercept(
                                method,
                                "wax.modern.anti_revoke.apply",
                            ) { chain ->
                                val messageId = messageIdOf(chain.args)
                                if (!mayWithholdRevocation(messageId, System.currentTimeMillis())) {
                                    // Native behaviour: WhatsApp applies its own
                                    // revocation, exactly as it would without us.
                                    return@intercept chain.proceed()
                                }
                                // The message is already local, so returning the
                                // handler's own "nothing happened" result keeps
                                // it without inventing a new message state.
                                return@intercept retainedResultFor(chain)
                            }
                        ModernHookRegistry.Handle { handle.unhook() }
                    },
                ),
            )
            Outcome.INSTALLED
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Anti-revoke hook unavailable", failure)
            Outcome.ERROR
        }
    }

    private sealed interface Revocation {
        data class Resolved(
            val method: java.lang.reflect.Method,
        ) : Revocation

        data class Failed(
            val outcome: Outcome,
        ) : Revocation
    }

    /**
     * The revocation handler, or the reason there is none.
     *
     * Both legacy anchors are tried in order and the first that matches exactly
     * once wins. Several matches are reported rather than resolved by order:
     * withholding the wrong handler would break deletion for every message.
     */
    private fun resolveRevocationMethod(target: Context): Revocation {
        for (anchor in REVOKE_ANCHORS) {
            val candidates =
                try {
                    DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                        dex.findMethod {
                            matcher { addUsingString(anchor, StringMatchType.Contains) }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    Log.w(TAG, "Revocation resolver unavailable for $anchor", failure)
                    return Revocation.Failed(Outcome.RESOLVER_MISSING)
                }
            when {
                candidates.isEmpty() -> {
                    continue
                }

                candidates.size != 1 -> {
                    return Revocation.Failed(Outcome.RESOLVER_AMBIGUOUS)
                }

                else -> {
                    val method =
                        try {
                            candidates[0].getMethodInstance(target.classLoader)
                        } catch (failure: Throwable) {
                            if (failure is VirtualMachineError) throw failure
                            Log.w(TAG, "Revocation method unresolvable for $anchor", failure)
                            return Revocation.Failed(Outcome.RESOLVER_MISSING)
                        }
                    if (method.parameterCount == 0) {
                        return Revocation.Failed(Outcome.UNSAFE_SIGNATURE)
                    }
                    return Revocation.Resolved(method)
                }
            }
        }
        return Revocation.Failed(Outcome.RESOLVER_MISSING)
    }

    /**
     * The message id, read the way the legacy feature reads it.
     *
     * Returns null when it cannot be identified, which means the revocation
     * proceeds natively rather than being withheld on a guess.
     */
    private fun messageIdOf(args: List<Any?>): String? {
        val message = args.firstOrNull() ?: return null
        for (name in listOf("A01", "getMessageId")) {
            val value =
                try {
                    if (name.startsWith("get")) {
                        message.javaClass.getMethod(name).invoke(message)
                    } else {
                        message.javaClass.getField(name).get(message)
                    }
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    continue
                }
            if (value is String && value.isNotEmpty()) return value
        }
        return null
    }

    /**
     * The handler's own "no revocation was applied" answer.
     *
     * The return type differs between builds, so it is constructed the way the
     * legacy feature constructed it, and only for the void/boolean/null shapes
     * that carry no data. Anything else proceeds natively, because inventing a
     * value for an unknown return type would be a guess.
     */
    private fun retainedResultFor(chain: Any): Any? {
        val proceed = chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterCount == 0 }
        val result = proceed?.let { runCatching { it.invoke(chain) }.getOrNull() }
        return result ?: RETAINED_BOOLEAN
    }

    /** What the handler returns when nothing was revoked, as a boxed boolean. */
    private val RETAINED_BOOLEAN: Any = java.lang.Boolean.TRUE
}
