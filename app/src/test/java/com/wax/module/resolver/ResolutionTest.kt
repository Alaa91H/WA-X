package com.wax.module.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionTest {
    private fun candidates(vararg names: String): List<String> = names.toList()

    // --- Resolved ---------------------------------------------------------------------

    @Test
    fun anExactResultCarriesItsValue() {
        val resolved = Resolution.exact("loadReceiptMethod")
        assertEquals("loadReceiptMethod", resolved.valueOrNull())
    }

    @Test
    fun anExactResultIsInstallable() {
        assertTrue(Resolution.exact("m").isInstallable)
    }

    @Test
    fun aLikelyResultCarriesItsValueButWeakerConfidence() {
        val likely = Resolution.likely("loadReceiptMethod", "name+signature")
        assertEquals("loadReceiptMethod", likely.valueOrNull())
        assertEquals(Confidence.LIKELY, likely.confidence)
        assertTrue("LIKELY is still installable under current policy", likely.isInstallable)
    }

    @Test
    fun aResolvedReasonNamesHowItMatched() {
        assertTrue(Resolution.exact("m", how = "exact signature").reason.contains("exact signature"))
    }

    // --- NotFound ---------------------------------------------------------------------

    @Test
    fun notFoundHasNoValue() {
        assertNull(Resolution.notFound("a", "b").valueOrNull())
    }

    @Test
    fun notFoundIsNotInstallable() {
        assertFalse(Resolution.notFound().isInstallable)
    }

    @Test
    fun notFoundConfidenceIsNone() {
        assertEquals(Confidence.NONE, Resolution.notFound().confidence)
    }

    @Test
    fun notFoundRemembersWhatItSearched() {
        val result = Resolution.NotFound(searched = candidates("LoadReceipt", "loadReceipt"))
        assertEquals(2, result.searched.size)
    }

    // --- Ambiguous --------------------------------------------------------------------

    @Test
    fun ambiguousHasNoValue() {
        assertNull(Resolution.Ambiguous(candidates("a", "b")).valueOrNull())
    }

    @Test
    fun ambiguousIsNeverInstallable() {
        // This is the invariant T11 exists to enforce: a guess must never become a hook.
        assertFalse(Resolution.Ambiguous(candidates("a", "b")).isInstallable)
    }

    @Test
    fun ambiguousConfidenceIsAmbiguous() {
        assertEquals(Confidence.AMBIGUOUS, Resolution.Ambiguous(candidates("a")).confidence)
    }

    @Test
    fun ambiguousKeepsItsCandidatesForDiagnostics() {
        val result = Resolution.Ambiguous(candidates("com.whatsapp.A", "com.whatsapp.B"))
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.contains("com.whatsapp.B"))
    }

    @Test
    fun ambiguousWithOneCandidateIsStillAmbiguous() {
        // The caller decides ambiguity; a defensive check here would hide a resolver bug.
        assertFalse(Resolution.Ambiguous(candidates("only")).isInstallable)
    }

    // --- Incompatible -----------------------------------------------------------------

    @Test
    fun incompatibleHasNoValue() {
        assertNull(Resolution.Incompatible("api too low").valueOrNull())
    }

    @Test
    fun incompatibleIsNotInstallable() {
        assertFalse(Resolution.Incompatible("api too low").isInstallable)
    }

    @Test
    fun incompatibleCarriesItsReasonAndDetail() {
        val result = Resolution.Incompatible("api too low", "requires 31, running 28")
        assertEquals("api too low", result.reason)
        assertEquals("requires 31, running 28", result.detail)
    }

    @Test
    fun incompatibleWithNoDetailIsStillUsable() {
        assertNull(Resolution.Incompatible("api too low").detail)
    }

    // --- candidate selection ----------------------------------------------------------

    @Test
    fun noCandidatesYieldsNotFound() {
        val result = Resolution.ofCandidates(emptyList<String>(), exact = true, how = "h") { it }
        assertTrue(result is Resolution.NotFound)
    }

    @Test
    fun oneExactCandidateYieldsExact() {
        val result = Resolution.ofCandidates(candidates("a"), exact = true, how = "h") { it }
        assertEquals(Confidence.EXACT, result.confidence)
        assertEquals("a", result.valueOrNull())
    }

    @Test
    fun oneHeuristicCandidateYieldsLikely() {
        val result = Resolution.ofCandidates(candidates("a"), exact = false, how = "h") { it }
        assertEquals(Confidence.LIKELY, result.confidence)
        assertTrue(result.isInstallable)
    }

    @Test
    fun severalCandidatesYieldAmbiguousEvenWhenTheLookupWasExact() {
        val result = Resolution.ofCandidates(candidates("a", "b"), exact = true, how = "h") { it }
        assertTrue(result is Resolution.Ambiguous)
        assertEquals(2, (result as Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun candidatesAreDescribedByTheSuppliedProjection() {
        val result =
            Resolution.ofCandidates(
                candidates("com.whatsapp.A", "com.whatsapp.B"),
                exact = true,
                how = "h",
            ) { it.substringAfterLast('.') }
        assertEquals(listOf("A", "B"), (result as Resolution.Ambiguous).candidates)
    }

    @Test
    fun theNotFoundPathRecordsTheStrategyThatWasUsed() {
        val result = Resolution.ofCandidates(emptyList<String>(), exact = true, how = "signature+arity") { it }
        assertEquals(listOf("signature+arity"), (result as Resolution.NotFound).searched)
    }

    // --- legacy lifting ---------------------------------------------------------------

    @Test
    fun aNonNullLegacyValueBecomesExact() {
        val result = Resolution.fromNullable<String>("found")
        assertEquals(Confidence.EXACT, result.confidence)
        assertEquals("found", result.valueOrNull())
    }

    @Test
    fun aNullLegacyValueBecomesNotFoundRatherThanThrowing() {
        val result = Resolution.fromNullable<String>(null)
        assertTrue(result is Resolution.NotFound)
        assertNull(result.valueOrNull())
    }

    @Test
    fun liftingPreservesTheCallersReason() {
        val result = Resolution.fromNullable<String>(null, reason = "no such method")
        assertEquals("no such method", result.reason)
    }

    // --- confidence policy ------------------------------------------------------------

    @Test
    fun exactAndLikelyAreInstallableAndTheRestAreNot() {
        assertTrue(Confidence.EXACT.isInstallable)
        assertTrue(Confidence.LIKELY.isInstallable)
        assertFalse(Confidence.AMBIGUOUS.isInstallable)
        assertFalse(Confidence.NONE.isInstallable)
    }

    @Test
    fun thePolicyIsAdjustableInOnePlace() {
        val original = Confidence.minimumToInstall
        try {
            assertTrue(Confidence.mayInstall(Confidence.LIKELY))
            assertFalse(Confidence.mayInstall(Confidence.AMBIGUOUS))
        } finally {
            assertEquals(original, Confidence.minimumToInstall)
        }
    }

    @Test
    fun everyOutcomeTypeIsCoveredByTheSealedHierarchy() {
        val outcomes: List<Resolution<String>> =
            listOf(
                Resolution.exact("a"),
                Resolution.NotFound(),
                Resolution.Ambiguous(candidates("a", "b")),
                Resolution.Incompatible("x"),
            )
        // Exhaustiveness matters: a new outcome must be visible here rather than defaulting
        // to "install it".
        assertEquals(4, outcomes.size)
        assertEquals(1, outcomes.count { it.isInstallable })
    }
}
