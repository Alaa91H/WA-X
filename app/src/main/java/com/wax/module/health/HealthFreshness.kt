package com.wax.module.health

/**
 * How old a report is, in the only three words the runtime needs for it.
 *
 * Freshness is separate from state on purpose. A target process that reported [READY]
 * four minutes ago and then died is not ready, and the state it last published cannot say
 * so by itself — the age of the report is the evidence. This is the distinction whose
 * absence makes a runtime show a green banner for a process that no longer exists.
 *
 * The thresholds are the modernization program's defaults and are part of the model
 * rather than of the UI, so every consumer agrees on where "stale" begins.
 */
enum class HealthFreshness {
    /** Reported within [FRESH_UNTIL_MILLIS]. */
    FRESH,

    /** Older than fresh, no older than [EXPIRED_AFTER_MILLIS]. */
    STALE,

    /** Older than [EXPIRED_AFTER_MILLIS]. */
    EXPIRED,

    /** No report at all, so there is nothing to age. */
    ABSENT,
    ;

    companion object {
        /** A report younger than this is fresh. */
        const val FRESH_UNTIL_MILLIS: Long = 30_000L

        /** A report older than this is expired; the boundary itself is still stale. */
        const val EXPIRED_AFTER_MILLIS: Long = 120_000L

        /**
         * Classifies an age in milliseconds.
         *
         * The boundaries are inclusive on the fresher side: exactly 30s is [STALE] and
         * exactly 120s is [STALE], and only beyond that is a report [EXPIRED]. A negative
         * age means the report's timestamp is in the future relative to [now], which is a
         * clock change rather than a fault in the report, so it is treated as [FRESH]
         * instead of being reported as impossible.
         */
        fun of(ageMillis: Long): HealthFreshness =
            when {
                ageMillis < 0L -> FRESH
                ageMillis < FRESH_UNTIL_MILLIS -> FRESH
                ageMillis <= EXPIRED_AFTER_MILLIS -> STALE
                else -> EXPIRED
            }

        /** Classifies a report at [timestampMillis] as observed at [nowMillis]. */
        fun at(
            timestampMillis: Long,
            nowMillis: Long,
        ): HealthFreshness =
            if (timestampMillis <= 0L) {
                ABSENT
            } else {
                of(nowMillis - timestampMillis)
            }
    }
}
