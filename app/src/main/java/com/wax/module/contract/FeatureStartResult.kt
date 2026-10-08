package com.wax.module.contract

/**
 * What starting a feature produced.
 *
 * The four states exist because a feature has four genuinely different outcomes and the previous
 * contract could express only two of them. `doHook()` returned `Unit`, so "installed", "not
 * applicable to this build", "installed but reduced" and "failed" were all the same event, and the
 * only way any of them became visible was a throwable caught by the installer.
 *
 * Nothing here is a health *verdict*. Whether a reduced feature makes the runtime degraded is the
 * runtime's decision, made from the feature's declared criticality and not from this result - the
 * same separation the bootstrap stages use.
 */
sealed interface FeatureStartResult {
    /** A one-line description of what happened, safe to write to a shared diagnostics document. */
    val summary: String

    /** The feature is fully installed. */
    data class Installed(
        override val summary: String,
        /** How many hooks were installed, for the installed-set count. */
        val hooks: Int = 0,
    ) : FeatureStartResult

    /** The feature is installed with reduced capability, and says why. */
    data class Degraded(
        override val summary: String,
        /** The capability that was lost. */
        val lost: String,
    ) : FeatureStartResult

    /** The feature does not apply to this target, and says what was missing. */
    data class Skipped(
        override val summary: String,
        /** What was missing. Not a failure: an unsupported build is an ordinary outcome. */
        val missing: String,
    ) : FeatureStartResult

    /** The feature could not be started. */
    data class Failed(
        override val summary: String,
        /** The stable code for how it failed. */
        val code: com.wax.module.diagnostics.FailureCode,
    ) : FeatureStartResult

    /** Whether the feature is doing something, in any form. */
    val isRunning: Boolean
        get() = this is Installed || this is Degraded

    /** Whether the feature is not doing anything, without that being a fault. */
    val isInapplicable: Boolean get() = this is Skipped
}
