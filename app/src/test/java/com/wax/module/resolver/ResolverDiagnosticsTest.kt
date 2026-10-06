package com.wax.module.resolver

import com.wax.module.diagnostics.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class ResolverDiagnosticsFailure(message: String) : RuntimeException(message)

class ResolverDiagnosticsTest {
    @Before
    fun setUp() {
        FeatureInstaller.clear()
        ResolverRegistry.clear()
        ResolverDiagnostics.clear()
    }

    /** The T15 acceptance scenario: WhatsApp renames something and a feature stops working. */
    private fun simulateWhatsAppUpdateBreakingAFeature() {
        val chain =
            FallbackChain
                .builder<String>("AntiRevoke")
                .primary { Resolution.NotFound("ReceiptHelper was renamed") }
                .build()
        FeatureInstaller.install("AntiRevoke", chain) { fail("must not install") }

        FeatureInstaller.install("HideSeen") { }
    }

    @Test
    fun aBrokenFeatureHasAVisibleReason() {
        simulateWhatsAppUpdateBreakingAFeature()
        val line = ResolverDiagnostics.report().first { it.featureId == "AntiRevoke" }
        assertTrue("every disable must have a visible reason", line.explanation.isNotBlank())
        assertTrue(line.explanation.contains("WhatsApp changed"))
    }

    @Test
    fun aBrokenFeatureHasAStableErrorCode() {
        simulateWhatsAppUpdateBreakingAFeature()
        val line = ResolverDiagnostics.report().first { it.featureId == "AntiRevoke" }
        assertEquals(FailureCode.RESOLVER_NOT_FOUND, line.code)
    }

    @Test
    fun aBrokenFeatureIsNotSilentlyDropped() {
        simulateWhatsAppUpdateBreakingAFeature()
        assertTrue(ResolverDiagnostics.hasSomethingToReport())
    }

    @Test
    fun healthyFeaturesAreExcludedFromTheNotableSet() {
        simulateWhatsAppUpdateBreakingAFeature()
        val notable = ResolverDiagnostics.notable().map { it.featureId }
        assertEquals(listOf("AntiRevoke"), notable)
    }

    @Test
    fun theNotableSetIsEmptyWhenEverythingWorks() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("B") { }
        assertFalse(ResolverDiagnostics.hasSomethingToReport())
    }

    @Test
    fun theRenderedTextNamesTheBrokenFeature() {
        simulateWhatsAppUpdateBreakingAFeature()
        val text = ResolverDiagnostics.render()
        assertTrue(text.contains("AntiRevoke"))
        assertTrue(text.contains("WhatsApp changed"))
    }

    @Test
    fun theRenderedTextOmitsHealthyFeaturesByDefault() {
        simulateWhatsAppUpdateBreakingAFeature()
        assertFalse(ResolverDiagnostics.render().contains("HideSeen"))
    }

    @Test
    fun theRenderedTextCanIncludeHealthyFeatures() {
        simulateWhatsAppUpdateBreakingAFeature()
        assertTrue(ResolverDiagnostics.render(includeHealthy = true).contains("HideSeen"))
    }

    @Test
    fun anAllClearReportSaysSoRatherThanShowingNothing() {
        FeatureInstaller.install("A") { }
        val text = ResolverDiagnostics.render()
        assertTrue(text.contains("No resolver problems"))
    }

    @Test
    fun theReportContainsNoUserData() {
        FeatureInstaller.install("Broken") {
            throw ResolverDiagnosticsFailure("failed for 4915112345678@s.whatsapp.net")
        }
        ResolverRegistry.record(
            "loadReceipt",
            "com.whatsapp.Receipt",
            Resolution.NotFound("no match for 120363000000000000@g.us"),
        )
        val text = ResolverDiagnostics.render(includeHealthy = true)
        assertFalse(text.contains("4915"))
        assertFalse(text.contains("120363"))
    }

    @Test
    fun aDisplayLineNamesTheFeatureAndState() {
        simulateWhatsAppUpdateBreakingAFeature()
        val line = ResolverDiagnostics.notable().single()
        val text = line.toDisplayLine()
        assertTrue(text.contains("AntiRevoke"))
        assertTrue(text.contains("NOT_RUNNING") || text.contains("FAILED") || text.contains("INCOMPATIBLE"))
    }

    // --- resolver state without a feature outcome ------------------------------------

    @Test
    fun anAmbiguousResolverOnItsOwnIsReportedAsFailed() {
        ResolverRegistry.record("loadReceipt", "com.whatsapp.Receipt", Resolution.Ambiguous(listOf("a", "b")))
        val line = ResolverDiagnostics.report().single()
        assertEquals(FeatureHealth.FAILED, line.health)
        assertEquals(FailureCode.RESOLVER_AMBIGUOUS, line.code)
    }

    @Test
    fun anAbsentResolverOnItsOwnIsReportedAsIncompatible() {
        ResolverRegistry.record("loadReceipt", "com.whatsapp.Receipt", Resolution.NotFound())
        assertEquals(FeatureHealth.INCOMPATIBLE, ResolverDiagnostics.report().single().health)
    }

    @Test
    fun aLikelyResolverOnItsOwnIsReportedAsDegraded() {
        ResolverRegistry.record("loadReceipt", "com.whatsapp.Receipt", Resolution.likely("m", "heuristic"))
        assertEquals(FeatureHealth.DEGRADED, ResolverDiagnostics.report().single().health)
    }

    @Test
    fun anExactResolverOnItsOwnIsReportedAsHealthy() {
        ResolverRegistry.record("loadReceipt", "com.whatsapp.Receipt", Resolution.exact("m"))
        assertEquals(FeatureHealth.HEALTHY, ResolverDiagnostics.report().single().health)
    }

    @Test
    fun aFeatureOutcomeTakesPrecedenceOverABareResolverRecord() {
        ResolverRegistry.record("AntiRevoke", "com.whatsapp.Receipt", Resolution.Ambiguous(listOf("a", "b")))
        FeatureInstaller.install("AntiRevoke") { }
        val line = ResolverDiagnostics.report().single { it.featureId == "AntiRevoke" }
        assertEquals("the installer knows more than the bare resolver", FeatureHealth.HEALTHY, line.health)
    }

    @Test
    fun anExplicitlyRecordedLineWins() {
        FeatureInstaller.install("A") { }
        ResolverDiagnostics.record(
            FeatureStatusLine("A", FeatureHealth.FAILED, "recorded later", FailureCode.UNEXPECTED),
        )
        assertEquals(FeatureHealth.FAILED, ResolverDiagnostics.report().first { it.featureId == "A" }.health)
    }

    @Test
    fun aFallbackFeatureIsExplainedAsCompatibility() {
        val chain =
            FallbackChain
                .builder<String>("X")
                .primary { Resolution.NotFound() }
                .fallback("compat") { Resolution.exact("m") }
                .build()
        FeatureInstaller.install("X", chain) { }
        val line = ResolverDiagnostics.report().first { it.featureId == "X" }
        assertEquals(FeatureHealth.FALLBACK, line.health)
        assertTrue(line.explanation.contains("compatibility path"))
    }

    @Test
    fun clearRemovesRecordedLines() {
        ResolverDiagnostics.record(FeatureStatusLine("X", FeatureHealth.FAILED, "x"))
        ResolverDiagnostics.clear()
        FeatureInstaller.clear()
        assertTrue(ResolverDiagnostics.report().isEmpty())
    }
}
