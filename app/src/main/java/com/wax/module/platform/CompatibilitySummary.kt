package com.wax.module.platform

/**
 * The overall verdict for one target package on one WhatsApp build.
 *
 * Three states, not two, because "works with limitations" is the common case after a
 * WhatsApp update and collapsing it into either "supported" or "broken" would either
 * overpromise or throw away a usable module.
 */
enum class CompatibilityStatus {
    /** Every critical resolver resolved on its primary path and nothing is held back. */
    SUPPORTED,

    /** The module works, with fallbacks, deferred features or unverified optional paths. */
    DEGRADED,

    /** At least one critical resolver failed; features that depend on it cannot run. */
    INCOMPATIBLE,
}

/**
 * The data behind the T84 summary card and the T65 release report.
 *
 * Both renderings come from this one object so the card in the app and the report attached
 * to a release cannot disagree. The card is the short form a user sees after an update;
 * the report is the long form that goes into release notes.
 */
data class CompatibilitySummary(
    val packageName: String,
    val whatsappVersion: String,
    val criticalPassed: Int,
    val criticalTotal: Int,
    val optionalPassed: Int,
    val optionalTotal: Int,
    val fallbacksActive: Int,
    val disabledFeatures: Int,
) {
    init {
        require(criticalPassed in 0..criticalTotal) { "criticalPassed must be within 0..criticalTotal" }
        require(optionalPassed in 0..optionalTotal) { "optionalPassed must be within 0..optionalTotal" }
        require(fallbacksActive >= 0) { "fallbacksActive must not be negative" }
        require(disabledFeatures >= 0) { "disabledFeatures must not be negative" }
    }

    /** Critical resolvers that did not resolve on the primary path. */
    val criticalFailures: Int get() = criticalTotal - criticalPassed

    /** The aggregate verdict. */
    val overall: CompatibilityStatus
        get() =
            when {
                // A critical failure is decisive: no amount of working optional paths makes the
                // features that depend on it usable.
                criticalTotal > 0 && criticalFailures > 0 -> CompatibilityStatus.INCOMPATIBLE

                // Zero verified critical resolvers is not "supported"; it means nothing was
                // verified, which the user deserves to know.
                criticalTotal == 0 -> CompatibilityStatus.DEGRADED

                optionalFailures > 0 || fallbacksActive > 0 || disabledFeatures > 0 ->
                    CompatibilityStatus.DEGRADED

                else -> CompatibilityStatus.SUPPORTED
            }

    /** Optional resolvers that did not resolve on the primary path. */
    val optionalFailures: Int get() = optionalTotal - optionalPassed

    /** All critical resolvers resolved. */
    val isCompatible: Boolean get() = criticalTotal > 0 && criticalFailures == 0

    /** The short card shown in the app after an update. */
    fun renderCard(): String =
        buildString {
            appendLine("Critical resolvers: $criticalPassed/$criticalTotal")
            appendLine("Optional resolvers: $optionalPassed/$optionalTotal")
            appendLine("Fallbacks active: $fallbacksActive")
            appendLine("Disabled features: $disabledFeatures")
            append("Overall: $overall")
        }

    /** The full report attached to a release or copied into a bug report. */
    fun renderReport(): String =
        buildString {
            appendLine("WA X Compatibility Report")
            appendLine()
            appendLine("Package: $packageName")
            appendLine("WhatsApp: $whatsappVersion")
            appendLine("Critical Resolvers: $criticalPassed/$criticalTotal")
            appendLine("Optional Resolvers: $optionalPassed/$optionalTotal")
            appendLine("Fallbacks: $fallbacksActive")
            appendLine("Disabled Features: $disabledFeatures")
            appendLine("Critical Failures: $criticalFailures")
            append("Overall: $overall")
        }

    companion object {
        /**
         * Builds a summary from resolver postures.
         *
         * Kept here rather than in the canary so any producer (canary, diagnostics screen,
         * release tooling) computes the counts the same way. A resolver counted as critical
         * is excluded from the optional totals even when a non-critical feature also lists
         * it, so the two lines always add up to the number of distinct resolvers checked.
         *
         * @param criticalResolvers resolvers required by critical features
         * @param optionalResolvers every other resolver the checked features reference
         * @param postureOf current state of each resolver; a missing entry counts as
         *   [ResolverPosture.MISSING]
         */
        fun from(
            packageName: String,
            whatsappVersion: String,
            criticalResolvers: Collection<String>,
            optionalResolvers: Collection<String>,
            disabledFeatures: Int,
            postureOf: (String) -> ResolverPosture,
        ): CompatibilitySummary {
            val critical = criticalResolvers.toSet()
            val optional = optionalResolvers.toSet() - critical

            // Each resolver is classified once, so a resolver shared by five features cannot
            // inflate the totals.
            val fallbacks = (critical + optional).count { postureOf(it) == ResolverPosture.FALLBACK }

            return CompatibilitySummary(
                packageName = packageName,
                whatsappVersion = whatsappVersion,
                criticalPassed = critical.count { postureOf(it) == ResolverPosture.RESOLVED },
                criticalTotal = critical.size,
                optionalPassed = optional.count { postureOf(it) == ResolverPosture.RESOLVED },
                optionalTotal = optional.size,
                fallbacksActive = fallbacks,
                disabledFeatures = disabledFeatures,
            )
        }
    }
}

/** How well one resolver is currently doing. */
enum class ResolverPosture {
    /** Resolved on its primary path with at least LIKELY confidence. */
    RESOLVED,

    /** Resolved, but through a compatibility fallback. */
    FALLBACK,

    /** Did not resolve on this build. */
    MISSING,
}
