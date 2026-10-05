package com.wmods.wppenhacer.resolver

import com.wmods.wppenhacer.diagnostics.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackChainTest {
    private fun <T> exact(value: T) = Resolution.exact(value)

    private fun <T> likely(value: T) = Resolution.likely(value, "heuristic")

    private fun <T> missing(): Resolution<T> = Resolution.NotFound()

    @Test
    fun thePrimaryPathIsUsedWhenItResolves() {
        var installed: String? = null
        val chain = FallbackChain.builder<String>("F").primary { exact("primary") }.build()
        val outcome = chain.run { installed = it }
        assertEquals("primary", installed)
        assertEquals(FeatureHealth.HEALTHY, outcome.health)
    }

    @Test
    fun thePrimaryPathIsNotConsultedAfterItSucceeds() {
        var fallbackRan = false
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { exact("primary") }
                .fallback("compat") {
                    fallbackRan = true
                    exact("compat")
                }.build()
        chain.run { }
        assertFalse(fallbackRan)
    }

    @Test
    fun aFallbackCarriesTheFeatureWhenThePrimaryMisses() {
        var installed: String? = null
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { missing() }
                .fallback("compat") { exact("compat") }
                .build()
        val outcome = chain.run { installed = it }
        assertEquals("compat", installed)
        assertEquals(FeatureHealth.FALLBACK, outcome.health)
        assertEquals("compat", outcome.usedFallback)
    }

    @Test
    fun fallbacksAreTriedInDeclarationOrder() {
        val tried = mutableListOf<String>()
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { missing() }
                .fallback("first") {
                    tried.add("first")
                    missing<String>()
                }.fallback("second") {
                    tried.add("second")
                    exact("second")
                }.build()
        val outcome = chain.run { }
        assertEquals(listOf("first", "second"), tried)
        assertEquals("second", outcome.usedFallback)
    }

    @Test
    fun theFeatureIsDisabledWhenNothingResolves() {
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { missing() }
                .fallback("compat") { missing() }
                .build()
        val outcome = chain.run { fail("must not install") }
        assertFalse(outcome.isRunning)
        assertEquals(FailureCode.RESOLVER_NOT_FOUND, outcome.code)
    }

    @Test
    fun aDisabledFeatureNamesItsResolver() {
        val chain = FallbackChain.builder<String>("AntiRevoke").primary { missing() }.build()
        assertTrue(
            chain.run { }.reason.contains("AntiRevoke") ||
                chain.run { }.toDisplayLine().contains("AntiRevoke"),
        )
    }

    @Test
    fun nothingIsInstalledWhenNothingResolves() {
        var installed = false
        val chain = FallbackChain.builder<String>("F").primary { missing() }.build()
        chain.run { installed = true }
        assertFalse(installed)
    }

    // --- confidence gating ------------------------------------------------------------

    @Test
    fun aLikelyPrimaryResolutionIsInstalledButDegraded() {
        var installed: String? = null
        val chain = FallbackChain.builder<String>("F").primary { likely("v") }.build()
        val outcome = chain.run { installed = it }
        assertEquals("v", installed)
        assertEquals(FeatureHealth.DEGRADED, outcome.health)
    }

    @Test
    fun anAmbiguousResultIsNeverInstalled() {
        var installed = false
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { Resolution.Ambiguous(listOf("a", "b")) }
                .build()
        val outcome = chain.run { installed = true }
        assertFalse("a guess must never become a hook", installed)
        assertFalse(outcome.isRunning)
    }

    @Test
    fun anAmbiguousPrimaryFallsThroughToTheNextPath() {
        var installed: String? = null
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { Resolution.Ambiguous(listOf("a", "b")) }
                .fallback("compat") { exact("compat") }
                .build()
        val outcome = chain.run { installed = it }
        assertEquals("compat", installed)
        assertEquals(FeatureHealth.FALLBACK, outcome.health)
    }

    @Test
    fun anIncompatibleResultIsNotInstalled() {
        var installed = false
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { Resolution.Incompatible("api too low") }
                .build()
        assertFalse(chain.run { installed = true }.isRunning)
        assertFalse(installed)
    }

    // --- isolation -------------------------------------------------------------------

    @Test
    fun aPrimaryThatThrowsDoesNotEscape() {
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { throw IllegalStateException("dexkit exploded") }
                .fallback("compat") { exact("compat") }
                .build()
        val outcome = chain.run { }
        assertEquals("compat", outcome.usedFallback)
    }

    @Test
    fun aPrimaryThatThrowsWithNoFallbackFailsTheFeatureOnly() {
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { throw IllegalStateException("dexkit exploded") }
                .build()
        val outcome = chain.run { fail("must not install") }
        assertFalse(outcome.isRunning)
    }

    @Test
    fun aFallbackThatThrowsDoesNotAbortTheChain() {
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { missing() }
                .fallback("broken") { throw RuntimeException("bad fallback") }
                .fallback("good") { exact("good") }
                .build()
        assertEquals("good", chain.run { }.usedFallback)
    }

    @Test
    fun anErrorThrownByInstallIsCapturedAsAFeatureFailure() {
        val chain = FallbackChain.builder<String>("F").primary { exact("v") }.build()
        val outcome = chain.run { throw IllegalArgumentException("hook refused") }
        assertFalse(outcome.isRunning)
        // An unrecognised throwable is honestly reported as UNEXPECTED rather than being
        // filed under a resolution code it did not come from.
        assertEquals(FailureCode.UNEXPECTED, outcome.code)
        assertTrue(outcome.reason.contains("IllegalArgumentException"))
    }

    @Test
    fun anErrorThrownInsideAnOutOfMemoryIsStillCaught() {
        // Even Errors must not take the module down; that is the isolation guarantee.
        val chain = FallbackChain.builder<String>("F").primary { exact("v") }.build()
        val outcome = chain.run { throw OutOfMemoryError("cannot continue") }
        assertFalse(outcome.isRunning)
    }

    // --- construction ----------------------------------------------------------------

    @Test
    fun aChainWithoutAPrimaryIsRejected() {
        val error =
            runCatching {
                FallbackChain.builder<String>("F").fallback("x") { exact("v") }.build()
            }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun aFallbackWithNoNameStillReports() {
        val chain =
            FallbackChain
                .builder<String>("F")
                .primary { missing() }
                .fallback("") { exact("v") }
                .build()
        assertEquals("", chain.run { }.usedFallback)
    }
}
