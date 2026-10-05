package com.wmods.wppenhacer.resolver

import com.wmods.wppenhacer.diagnostics.FailureCode

/**
 * The one place that decides what happens when a dependency does not resolve.
 *
 * The rule the plan states is `primary → compatibility fallback → disable the feature`, and
 * the reason it has to live in one place is that getting it wrong is silent. A feature
 * installed on the wrong member looks like it works until it corrupts something; a feature
 * left running with a null dependency crashes later, somewhere unrelated. Centralising the
 * decision means every feature gets the same answer, and the answer is testable without a
 * device.
 *
 * @param T what the dependency produces
 */
class FallbackChain<T : Any> private constructor(
    private val featureId: String,
    private val primary: () -> Resolution<T>,
    private val fallbacks: List<Fallback<T>>
) {

    /** A named alternative path, tried in declaration order. */
    data class Fallback<T : Any>(val name: String, val resolve: () -> Resolution<T>)

    /**
     * Runs the chain and reports what happened.
     *
     * @param install called only when a dependency resolved and may be installed. Kept as a
     *   parameter so the policy can be tested without a class loader or a real hook.
     * @return the outcome; this never throws for an unresolvable dependency, and never
     *   returns a running state with a missing value
     */
    fun run(install: (T) -> Unit): FeatureOutcome {
        // Written as an explicit `if` rather than `?: run { ... }`: inside a member named
        // `run`, the elvis form resolves back to this method and recurses until the stack
        // overflows.
        val primaryResolved = attempt(primary)
        if (primaryResolved != null) {
            return finish(primaryResolved, fallbackName = null, install = install)
        }

        val firstFailure = FeatureOutcome.failed(
            featureId,
            FailureCode.RESOLVER_NOT_FOUND,
            "the primary resolver found nothing on this WhatsApp build"
        )

        // Compatibility fallbacks, in order.
        for (fallback in fallbacks) {
            val resolved = attempt(fallback.resolve) ?: continue
            return finish(resolved, fallbackName = fallback.name, install = install)
        }

        return firstFailure
    }

    /**
     * Runs one path and installs on success.
     *
     * A throwable escaping a resolver is turned into a failed outcome rather than
     * propagated: one broken resolver must not abort the chain, let alone the rest of the
     * module.
     */
    private fun finish(
        resolved: Resolution.Resolved<T>,
        fallbackName: String?,
        install: (T) -> Unit
    ): FeatureOutcome {
        if (!Confidence.mayInstall(resolved.confidence)) {
            return FeatureOutcome.degraded(
                featureId,
                "resolved at ${resolved.confidence} confidence, which is below the " +
                        "install threshold, so the hook was not installed"
            ).copy(health = FeatureHealth.FAILED)
        }

        // Installation is where a resolver's value is actually used, and it can throw for
        // reasons the resolver never saw. Isolating it here is what keeps the failure
        // attached to this feature only.
        try {
            install(resolved.value)
        } catch (error: Throwable) {
            return FeatureOutcome.failed(
                featureId,
                FailureCode.classify(error, "install"),
                "installing the hook failed: ${error.javaClass.simpleName}"
            )
        }

        return if (fallbackName != null) {
            FeatureOutcome.fallback(
                featureId,
                fallbackName,
                "the primary path did not resolve, so the ${fallbackName} fallback was used"
            )
        } else if (resolved.confidence == Confidence.LIKELY) {
            FeatureOutcome.degraded(
                featureId,
                "resolved only heuristically, so the hook is installed but unverified"
            )
        } else {
            FeatureOutcome.healthy(featureId)
        }
    }

    /**
     * Runs a resolution path, converting any throwable into "did not resolve".
     *
     * Returns null when the path produced no installable value.
     */
    private fun attempt(resolve: () -> Resolution<T>): Resolution.Resolved<T>? = try {
        val outcome = resolve()
        outcome as? Resolution.Resolved<T>
    } catch (_: Throwable) {
        null
    }

    class Builder<T : Any>(private val featureId: String) {
        private var primary: (() -> Resolution<T>)? = null
        private val fallbacks = ArrayList<Fallback<T>>()

        fun primary(resolve: () -> Resolution<T>): Builder<T> = apply {
            primary = resolve
        }

        fun fallback(name: String, resolve: () -> Resolution<T>): Builder<T> = apply {
            fallbacks.add(Fallback(name, resolve))
        }

        fun build(): FallbackChain<T> {
            val primaryPath = primary
                ?: throw IllegalArgumentException("$featureId: a fallback chain needs a primary path")
            return FallbackChain(featureId, primaryPath, fallbacks.toList())
        }
    }

    companion object {
        /** Starts a chain for [featureId]. */
        fun <T : Any> builder(featureId: String): Builder<T> = Builder(featureId)
    }
}