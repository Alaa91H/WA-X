package com.wax.module.platform

import com.wax.module.resolver.Confidence

/**
 * Counts process starts that never reached a healthy initialisation.
 *
 * The counter has to be written *before* the risky work starts and cleared only after it
 * finishes, because the failure mode being detected is a crash during that window: nothing
 * after the crash runs, so a success signal alone can never detect it. Writing the attempt
 * first is what turns "the app crashed three times" into a readable fact on the fourth
 * start.
 */
class StartupGuard(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Records that a process start is beginning. Call before any feature loads. */
    fun markStartupAttempt() {
        store.putInt(KEY_FAILURES, store.getInt(KEY_FAILURES) + 1)
        store.putLong(KEY_LAST_ATTEMPT, now())
    }

    /** Records that initialisation completed. Call after the critical features are up. */
    fun markStartupSuccess() {
        store.putInt(KEY_FAILURES, 0)
        store.putLong(KEY_LAST_SUCCESS, now())
    }

    /** Consecutive starts since the last recorded success, including the current one. */
    fun consecutiveFailedStarts(): Int = store.getInt(KEY_FAILURES)

    /** Whether the module is in a crash loop by the T80 threshold. */
    fun isCrashLooping(): Boolean = consecutiveFailedStarts() >= CRASH_LOOP_THRESHOLD

    /** When initialisation last completed, or 0 when it never has. */
    fun lastSuccessfulStart(): Long = store.getLong(KEY_LAST_SUCCESS)

    /** Drops the counters. Used by tests and by Safe Mode recovery. */
    fun clear() {
        store.remove(KEY_FAILURES)
        store.remove(KEY_LAST_ATTEMPT)
        store.remove(KEY_LAST_SUCCESS)
    }

    companion object {
        /** The T80 trigger: repeated startup crashes. */
        const val CRASH_LOOP_THRESHOLD: Int = 3

        private const val KEY_FAILURES = "wae.startup.failures"
        private const val KEY_LAST_ATTEMPT = "wae.startup.last_attempt"
        private const val KEY_LAST_SUCCESS = "wae.startup.last_success"
    }
}

/** Why Safe Mode is active. Shown verbatim to the user, so each reason is actionable. */
enum class SafeModeReason(
    val description: String,
) {
    /** The user asked for it. */
    MANUAL("Safe Mode was turned on manually."),

    /** The module crashed during startup repeatedly. */
    REPEATED_STARTUP_CRASHES(
        "WA X failed to start repeatedly, so it loaded the recovery tools only. " +
            "You can leave Safe Mode once it starts normally.",
    ),

    /** A resolver the platform depends on failed on this WhatsApp build. */
    CRITICAL_RESOLVER_FAILURE(
        "A required compatibility check failed on this WhatsApp version, so risky features " +
            "were held back.",
    ),

    /** The declared compatibility confidence dropped below what the gate accepts. */
    LOW_COMPATIBILITY_CONFIDENCE(
        "This WhatsApp version is not well understood yet, so WA X started conservatively.",
    ),
}

/**
 * The T80 recovery path: a startup that loads the minimum, explains itself, and lets the
 * user get back to normal without uninstalling.
 *
 * Safe Mode is a *state*, not a code path that silently changes behaviour: it is persisted,
 * it is visible in the UI, and it names its reason. The feature filter ([allows]) is the
 * enforcement point, and it deliberately allows only [PlatformFeatures.SAFE_MODE_RECOVERY]
 * — diagnostics, compatibility, settings and the kill switch — because "safe mode" that
 * still loads the features suspected of crashing is not safe.
 */
class SafeModeController(
    private val store: KeyValueStore,
    private val guard: StartupGuard,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** Whether Safe Mode is currently active. */
    fun isActive(): Boolean = synchronized(lock) { store.getBoolean(KEY_ACTIVE, false) }

    /** Why Safe Mode is active, or null when it is not. */
    fun reason(): SafeModeReason? =
        synchronized(lock) {
            if (!isActive()) return@synchronized null
            store.getString(KEY_REASON)?.let { stored ->
                SafeModeReason.entries.firstOrNull { it.name == stored }
            }
        }

    /** When Safe Mode was entered, or 0 when it never was. */
    fun enteredAt(): Long = synchronized(lock) { store.getLong(KEY_ENTERED_AT) }

    /** The detail recorded with the reason, for diagnostics. */
    fun detail(): String? = synchronized(lock) { store.getString(KEY_DETAIL) }

    /** Enters Safe Mode for [reason], recording [detail] for diagnostics. */
    fun enter(
        reason: SafeModeReason,
        detail: String? = null,
    ): Boolean =
        synchronized(lock) {
            store.putBoolean(KEY_ACTIVE, true)
            store.putString(KEY_REASON, reason.name)
            store.putLong(KEY_ENTERED_AT, now())
            if (detail.isNullOrBlank()) store.remove(KEY_DETAIL) else store.putString(KEY_DETAIL, detail)
            true
        }

    /** Leaves Safe Mode. The startup guard is reset so recovery is not immediately re-triggered. */
    fun exit(): Boolean =
        synchronized(lock) {
            store.remove(KEY_ACTIVE)
            store.remove(KEY_REASON)
            store.remove(KEY_DETAIL)
            store.remove(KEY_ENTERED_AT)
            guard.markStartupSuccess()
            true
        }

    /** The explicit user request path. */
    fun requestManual(detail: String? = null): Boolean = enter(SafeModeReason.MANUAL, detail)

    /**
     * The three automatic triggers of T80, evaluated in priority order.
     *
     * Ordering matters: a crash loop is both the most severe and the most actionable, and
     * reporting it as "low confidence" would hide the fact that recovery is needed.
     *
     * @param criticalResolverFailures the number of required resolvers that failed
     * @param compatibilityConfidence the platform's aggregate confidence in this build
     * @return the reason Safe Mode was entered for, or null when it stayed off
     */
    fun evaluateAutomaticTriggers(
        criticalResolverFailures: Int,
        compatibilityConfidence: Confidence,
    ): SafeModeReason? =
        synchronized(lock) {
            if (isActive()) return@synchronized reason()

            val triggered =
                when {
                    guard.isCrashLooping() ->
                        SafeModeReason.REPEATED_STARTUP_CRASHES to
                            "${guard.consecutiveFailedStarts()} consecutive starts without completing initialisation"

                    criticalResolverFailures > 0 ->
                        SafeModeReason.CRITICAL_RESOLVER_FAILURE to
                            "$criticalResolverFailures critical resolver(s) failed"

                    compatibilityConfidence == Confidence.NONE || compatibilityConfidence == Confidence.AMBIGUOUS ->
                        SafeModeReason.LOW_COMPATIBILITY_CONFIDENCE to
                            "compatibility confidence is ${compatibilityConfidence.name}"

                    else -> return@synchronized null
                }

            enter(triggered.first, triggered.second)
            triggered.first
        }

    /**
     * Whether [featureId] may load under the current state.
     *
     * Outside Safe Mode every feature is allowed; the kill switch handles individual ones.
     * Inside, only the recovery set is allowed — this is the "loads only diagnostics,
     * compatibility engine, settings, minimal recovery hooks" rule.
     */
    fun allows(featureId: String): Boolean = !isActive() || PlatformFeatures.SAFE_MODE_RECOVERY.contains(featureId)

    /** Filters a registry list down to what Safe Mode would load. */
    fun allowedFeatures(features: List<FeatureMetadata>): List<FeatureMetadata> = features.filter { allows(it.id) }

    /** A user-facing explanation suitable for the diagnostics screen or a toast. */
    fun describe(): String =
        synchronized(lock) {
            if (!isActive()) return@synchronized "Safe Mode is off."
            buildString {
                appendLine("Safe Mode is ON.")
                appendLine(reason()?.description ?: SafeModeReason.MANUAL.description)
                detail()?.let { appendLine("Details: $it") }
                appendLine(
                    "Loaded tools: diagnostics, compatibility, settings and recovery only.",
                )
            }
        }

    /** Drops the Safe Mode state. Used by tests and by a factory reset. */
    fun clear() =
        synchronized(lock) {
            store.keys(KEY_PREFIX).forEach { store.remove(it) }
        }

    companion object {
        private const val KEY_PREFIX = "wae.safemode."
        private const val KEY_ACTIVE = "wae.safemode.active"
        private const val KEY_REASON = "wae.safemode.reason"
        private const val KEY_DETAIL = "wae.safemode.detail"
        private const val KEY_ENTERED_AT = "wae.safemode.entered_at"
    }
}
