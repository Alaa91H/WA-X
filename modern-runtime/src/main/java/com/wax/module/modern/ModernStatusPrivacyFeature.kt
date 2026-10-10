package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap

/**
 * Status seen privacy, distinct from chat receipts (#452).
 *
 * A Status acknowledgement is not a chat read receipt. WhatsApp sends them on
 * different paths, to different senders, with different semantics, and a change
 * to one says nothing about the other. This feature therefore never reuses the
 * chat receipt hook from #449 and never reports under a chat receipt id: a user
 * who hides Status views has not touched their chat receipts, and a report that
 * blurred the two would be wrong in the direction that matters.
 *
 * Two switches, and the boundary between them:
 *
 * - **Hide Status Viewed** withholds the acknowledgement the playback page
 *   sends when a Status is opened.
 * - **Send Seen When I Reply** is owned by #357, which holds the single Status
 *   seen-receipt rule. This feature does not reimplement it and reports it as
 *   pending rather than shipping a second state machine that could disagree
 *   with the first.
 *
 * Every failure falls back to native behaviour. Status viewing keeps working;
 * only the acknowledgement this module was asked to withhold does not go out.
 */
object ModernStatusPrivacyFeature {
    const val FEATURE_ID_SEEN = "status_seen_hidden"
    const val FEATURE_ID_AFTER_REPLY = "status_seen_after_reply"

    /** Legacy key, so an existing user's switch keeps its meaning. */
    const val PREF_HIDE_STATUS_VIEW = "hidestatusview"

    /**
     * The playback-page anchors the legacy resolver found.
     *
     * `PLAYBACK_PAGE_ITEM_ON_CREATE_VIEW_END` is the point at which the "seen"
     * mark is attached; `StatusPlaybackPage/onViewCreated` is the page itself.
     * The first is what is actually withheld, and the second is only used to
     * confirm the page really is the Status playback page.
     */
    val VIEW_ANCHORS =
        listOf("PLAYBACK_PAGE_ITEM_ON_CREATE_VIEW_END", "StatusPlaybackPage/onViewCreated")

    private const val TAG = "WA-X StatusPrivacy102"

    enum class Outcome {
        DISABLED,
        INSTALLED,

        /** The anchor was not found on this build. */
        RESOLVER_MISSING,

        /** Several methods matched; nothing was hooked. */
        RESOLVER_AMBIGUOUS,

        /** The anchor resolved but is not the shape this relies on. */
        UNSAFE_SIGNATURE,

        /**
         * Owned by #357, which is not on the API 102 runtime yet.
         *
         * Reporting this rather than implementing it a second time is what
         * keeps one Status seen rule instead of two that can disagree.
         */
        OWNED_BY_357,

        ERROR,
    }

    /**
     * Status items that have been replied to, per status author.
     *
     * Keyed by an opaque author key, never a number or a JID. This exists only
     * to let #357 decide whether a reply happened; until then it stays empty,
     * which is the honest state.
     */
    private val repliedStatusAuthors = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun repliedAuthorCount(): Int = repliedStatusAuthors.size

    @JvmStatic
    fun clearRepliedAuthors() {
        repliedStatusAuthors.clear()
    }

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Map<String, Outcome> {
        val results = LinkedHashMap<String, Outcome>()
        // #357 owns the reply rule. Until it is on this runtime, saying so is
        // the only honest answer; implementing it here would be the competing
        // state machine the issue forbids.
        results[FEATURE_ID_AFTER_REPLY] = Outcome.OWNED_BY_357

        if (!preferences.getBoolean(PREF_HIDE_STATUS_VIEW, false)) {
            results[FEATURE_ID_SEEN] = Outcome.DISABLED
            return results
        }
        val resolved = resolveSeenAnchor(target)
        if (resolved is Resolution.Failed) {
            results[FEATURE_ID_SEEN] = resolved.outcome
            return results
        }
        val method = (resolved as Resolution.Resolved).method

        results[FEATURE_ID_SEEN] =
            try {
                hooks.installFeature(
                    FEATURE_ID_SEEN,
                    listOf(
                        ModernHookRegistry.Registration("status_seen.withhold") {
                            val handle =
                                ModernHookBridge(framework).intercept(
                                    method,
                                    "wax.modern.status_seen.withhold",
                                ) { chain ->
                                    // The legacy feature stopped the "viewed" mark
                                    // here. Doing the same leaves playback intact
                                    // and only withholds the acknowledgement.
                                    null
                                }
                            ModernHookRegistry.Handle { handle.unhook() }
                        },
                    ),
                )
                Outcome.INSTALLED
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Status seen hook unavailable", failure)
                Outcome.ERROR
            }
        return results
    }

    private sealed interface Resolution {
        data class Resolved(
            val method: java.lang.reflect.Method,
        ) : Resolution

        data class Failed(
            val outcome: Outcome,
        ) : Resolution
    }

    /**
     * The acknowledgement anchor, or the reason there is none.
     *
     * Anchors are tried in order and only a single match is accepted. Two
     * matches means this is not the method the legacy feature used, and
     * hooking it would withhold the wrong thing.
     */
    private fun resolveSeenAnchor(target: Context): Resolution {
        for (anchor in VIEW_ANCHORS) {
            val candidates =
                try {
                    DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                        dex.findMethod {
                            matcher { addUsingString(anchor, StringMatchType.Contains) }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    Log.w(TAG, "Status anchor unavailable for $anchor", failure)
                    return Resolution.Failed(Outcome.RESOLVER_MISSING)
                }
            when {
                candidates.isEmpty() -> {
                    continue
                }

                candidates.size != 1 -> {
                    return Resolution.Failed(Outcome.RESOLVER_AMBIGUOUS)
                }

                else -> {
                    val method =
                        try {
                            candidates[0].getMethodInstance(target.classLoader)
                        } catch (failure: Throwable) {
                            if (failure is VirtualMachineError) throw failure
                            Log.w(TAG, "Status method unresolvable for $anchor", failure)
                            return Resolution.Failed(Outcome.RESOLVER_MISSING)
                        }
                    if (method.parameterCount > MAX_PLAYBACK_PARAMETERS) {
                        return Resolution.Failed(Outcome.UNSAFE_SIGNATURE)
                    }
                    return Resolution.Resolved(method)
                }
            }
        }
        return Resolution.Failed(Outcome.RESOLVER_MISSING)
    }

    /**
     * The widest playback-page signature this feature accepts.
     *
     * The playback view binds a few arguments; anything larger is a different
     * method that merely shares the anchor, and suppressing that would be a
     * guess.
     */
    private const val MAX_PLAYBACK_PARAMETERS = 4
}
