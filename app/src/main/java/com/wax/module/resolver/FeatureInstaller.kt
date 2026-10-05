package com.wax.module.resolver

import java.util.Collections

/**
 * Installs features while guaranteeing that one failure cannot affect the others.
 *
 * This is the plan's feature-isolation rule made executable: a feature that throws during
 * installation is recorded and skipped, and every remaining feature still runs. Without
 * this, a single unresolvable dependency on a WhatsApp update would abort the whole
 * startup path and the user would lose the entire module rather than one toggle.
 */
object FeatureInstaller {
    private val outcomes = Collections.synchronizedList(ArrayList<FeatureOutcome>())

    /**
     * Installs [featureId] by running [install], capturing any failure.
     *
     * @param install the feature's own installation; may throw freely
     * @return the outcome, never null
     */
    fun install(
        featureId: String,
        install: () -> Unit,
    ): FeatureOutcome {
        val outcome =
            try {
                install()
                FeatureOutcome.healthy(featureId)
            } catch (error: Throwable) {
                FeatureOutcome.failed(
                    featureId,
                    com.wax.module.diagnostics.FailureCode
                        .classify(error, "install"),
                    "installing the feature failed: ${error.javaClass.simpleName}",
                )
            }
        outcomes.add(outcome)
        return outcome
    }

    /**
     * Installs [featureId] through a [FallbackChain].
     *
     * Separated from [install] so the chain's own decision is not lost when installation
     * throws: a fallback feature that still fails to install is reported as failed with the
     * fallback it reached, which is more useful than "install error" alone.
     */
    fun install(
        featureId: String,
        chain: FallbackChain<*>,
        install: () -> Unit,
    ): FeatureOutcome {
        val outcome =
            try {
                chain.run { install() }
            } catch (error: Throwable) {
                FeatureOutcome.failed(
                    featureId,
                    com.wax.module.diagnostics.FailureCode
                        .classify(error, "install"),
                    "installing the feature failed: ${error.javaClass.simpleName}",
                )
            }
        outcomes.add(outcome)
        return outcome
    }

    /** Records an outcome produced elsewhere, for example by a resolver that ran earlier. */
    fun record(outcome: FeatureOutcome) {
        outcomes.add(outcome)
    }

    /** Every outcome recorded this session, in installation order. */
    fun all(): List<FeatureOutcome> = synchronized(outcomes) { outcomes.toList() }

    /** The outcome for [featureId], or null when it was never attempted. */
    fun outcomeFor(featureId: String): FeatureOutcome? = synchronized(outcomes) { outcomes.lastOrNull { it.featureId == featureId } }

    /** Features that are running in some form. */
    fun running(): List<FeatureOutcome> = all().filter { it.isRunning }

    /** Features that are not running, which is what a user needs to be told about. */
    fun stopped(): List<FeatureOutcome> = all().filterNot { it.isRunning }

    /** Outcomes worth surfacing, which excludes HEALTHY and UNKNOWN. */
    fun notable(): List<FeatureOutcome> = all().filter { it.health.isNotable }

    /** Drops all recorded outcomes. */
    fun clear() {
        synchronized(outcomes) { outcomes.clear() }
    }
}
