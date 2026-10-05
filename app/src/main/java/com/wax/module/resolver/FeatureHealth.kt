package com.wax.module.resolver

/**
 * The health of one feature after its dependencies have been resolved.
 *
 * These states are the vocabulary the plan's standard health states use, and they are
 * deliberately distinguishable from each other: "running with an older code path" and
 * "not running at all" call for different user actions, so they must not collapse into a
 * single "disabled".
 */
enum class FeatureHealth {

    /** Installed on the primary path with no fallbacks involved. */
    HEALTHY,

    /**
     * Installed, but only after a fallback was used, or with a resolver that resolved at
     * LIKELY rather than EXACT confidence. Works, with less certainty than HEALTHY.
     */
    DEGRADED,

    /** Running through a compatibility path that exists specifically for older builds. */
    FALLBACK,

    /** Not installed, and installing it is not currently possible. */
    DISABLED,

    /** Not installed because a required target does not exist on this WhatsApp build. */
    INCOMPATIBLE,

    /** Not installed because resolution was ambiguous or resolution itself failed. */
    FAILED,

    /** Never attempted, or the outcome was not recorded. */
    UNKNOWN;

    /** Whether the feature is running in any form. */
    val isRunning: Boolean
        get() = this == HEALTHY || this == DEGRADED || this == FALLBACK

    /** Whether a user would benefit from being told about this state. */
    val isNotable: Boolean
        get() = this != HEALTHY && this != UNKNOWN
}