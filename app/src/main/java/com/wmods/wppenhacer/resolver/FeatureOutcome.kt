package com.wmods.wppenhacer.resolver

/**
 * What happened to one feature, in a form that can be shown to a user and aggregated by
 * tooling.
 *
 * @param featureId the feature's stable id
 * @param health the resulting state
 * @param reason a short, user-readable explanation. Never contains user data; see
 *   `ReportRedactor`.
 * @param code the machine-readable failure code, when the feature did not start
 * @param usedFallback the name of the fallback that carried the feature, when one did
 */
data class FeatureOutcome(
    val featureId: String,
    val health: FeatureHealth,
    val reason: String,
    val code: com.wmods.wppenhacer.diagnostics.FailureCode? = null,
    val usedFallback: String? = null
) {

    /** Whether the feature is running in some form. */
    val isRunning: Boolean get() = health.isRunning

    /** One line suitable for a diagnostics list. */
    fun toDisplayLine(): String = buildString {
        append(featureId)
        append(" [")
        append(health)
        append(']')
        if (usedFallback != null) {
            append(" via ")
            append(usedFallback)
        }
        append(": ")
        append(reason)
    }

    companion object {

        /** The feature installed on its primary path. */
        fun healthy(featureId: String, detail: String = "resolved on the primary path"): FeatureOutcome =
            FeatureOutcome(featureId, FeatureHealth.HEALTHY, detail)

        /**
         * The feature installed, but on a weaker path than intended.
         *
         * @param detail what was weakened: a LIKELY resolution, or the fallback used
         */
        fun degraded(featureId: String, detail: String, usedFallback: String? = null): FeatureOutcome =
            FeatureOutcome(featureId, FeatureHealth.DEGRADED, detail, usedFallback = usedFallback)

        /** The feature installed through a compatibility path. */
        fun fallback(featureId: String, fallbackName: String, detail: String): FeatureOutcome =
            FeatureOutcome(featureId, FeatureHealth.FALLBACK, detail, usedFallback = fallbackName)

        /** The feature could not be installed and no fallback applied. */
        fun disabled(
            featureId: String,
            code: com.wmods.wppenhacer.diagnostics.FailureCode,
            detail: String
        ): FeatureOutcome = FeatureOutcome(featureId, FeatureHealth.DISABLED, detail, code)

        /** The feature cannot work on this WhatsApp build at all. */
        fun incompatible(featureId: String, detail: String): FeatureOutcome =
            FeatureOutcome(featureId, FeatureHealth.INCOMPATIBLE, detail)

        /** Resolution was ambiguous or the feature's own installation threw. */
        fun failed(
            featureId: String,
            code: com.wmods.wppenhacer.diagnostics.FailureCode,
            detail: String
        ): FeatureOutcome = FeatureOutcome(featureId, FeatureHealth.FAILED, detail, code)
    }
}