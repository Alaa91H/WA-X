package com.wax.module.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end checks for the T03 acceptance criteria: a simulated feature failure has to
 * yield a machine readable report that carries no user data.
 *
 * These deliberately build the throwable the way a real resolver failure looks — with a
 * WhatsApp identifier in the message — because that is the case the redaction exists for.
 */
class SimulatedFeatureFailureTest {
    /** Mimics a DexKit resolver failing while handling a chat. */
    private fun resolverFailure(): Throwable {
        val failure =
            IllegalStateException(
                "No match found for message view in chat 4915112345678@s.whatsapp.net " +
                    "while processing [this is a private message the user sent]",
            )
        failure.stackTrace =
            arrayOf(
                StackTraceElement("com.wax.module.xposed.core.devkit.Unobfuscator", "loadMessageViewClass", "Unobfuscator.kt", 412),
                StackTraceElement("com.wax.module.xposed.features.general.AntiRevoke", "doHook", "AntiRevoke.kt", 118),
                StackTraceElement("android.view.View", "findViewById", "View.java", 1),
            )
        return failure
    }

    private fun simulate(): FeatureFailureReport =
        FeatureFailureReport.fromThrowable(
            featureId = "AntiRevoke",
            throwable = resolverFailure(),
            moduleVersion = "1.6.2-dev+544991A8",
            whatsappVersion = "2.26.40.21",
            packageName = "com.whatsapp",
            resolver = "loadMessageViewClass",
            stage = "resolve",
            timestampMillis = 1_700_000_000_000L,
            threadName = "WAE-HookInstaller",
        )

    @Test
    fun theReportIsMachineReadable() {
        val report = simulate()
        val parsed = FailureReportParser.parseOne(FailureReportCodec.encode(report))
        assertNotNull("the emitted report must parse back", parsed)
        assertEquals(report, parsed)
    }

    @Test
    fun theReportAnswersWhichFeatureFailed() {
        assertEquals("AntiRevoke", simulate().featureId)
    }

    @Test
    fun theReportAnswersWhichResolverFailed() {
        assertEquals("loadMessageViewClass", simulate().resolver)
    }

    @Test
    fun theReportAnswersWhichWhatsAppBuildFailed() {
        assertEquals("2.26.40.21", simulate().whatsappVersion)
    }

    @Test
    fun theReportAnswersWhichModuleVersionFailed() {
        assertEquals("1.6.2-dev+544991A8", simulate().moduleVersion)
    }

    @Test
    fun theReportCarriesAStableErrorCode() {
        assertNotNull(simulate().code)
        assertEquals(FailureCode.UNEXPECTED, simulate().code)
    }

    @Test
    fun aResolverStageHintIsReflectedInTheCode() {
        val ambiguous =
            FeatureFailureReport.fromThrowable(
                featureId = "Others",
                throwable = resolverFailure(),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
                stage = "resolve: ambiguous",
            )
        assertEquals(FailureCode.RESOLVER_AMBIGUOUS, ambiguous.code)
    }

    // --- the privacy guarantee --------------------------------------------------------

    @Test
    fun noJidSurvivesIntoTheReport() {
        val encoded = FailureReportCodec.encode(simulate())
        assertFalse(encoded.contains("4915112345678"))
        assertFalse(encoded.contains("s.whatsapp.net"))
    }

    @Test
    fun noMessageContentSurvivesIntoTheReport() {
        val encoded = FailureReportCodec.encode(simulate())
        assertFalse(encoded.contains("private message"))
    }

    @Test
    fun noUserDataSurvivesIntoTheShareableText() {
        val text = FailureReportCodec.renderText(listOf(simulate()))
        assertFalse(text.contains("4915"))
        assertFalse(text.contains("private message"))
        assertTrue(text.contains(ReportRedactor.PLACEHOLDER))
    }

    @Test
    fun thePlatformFrameIsDroppedButTheModuleFramesRemain() {
        val frames = simulate().frames
        assertFalse(frames.any { it.contains("android.view.View") })
        assertTrue(frames.any { it.contains("Unobfuscator.loadMessageViewClass") })
        assertTrue(frames.any { it.contains("AntiRevoke.doHook") })
    }

    @Test
    fun theFramesPointAtTheResolverSoTheFailureIsActionable() {
        // The whole point of the report: a maintainer can see which resolver to fix.
        assertTrue(simulate().frames.any { it.endsWith("Unobfuscator.loadMessageViewClass") })
    }

    @Test
    fun theBuildContextIsPreservedDespiteRedaction() {
        val report = simulate()
        assertEquals("2.26.40.21", report.whatsappVersion)
        assertEquals("1.6.2-dev+544991A8", report.moduleVersion)
        assertEquals("com.whatsapp", report.packageName)
    }

    @Test
    fun aWholeSessionOfFailuresRoundTrips() {
        val reports =
            listOf(
                simulate(),
                simulate().copy(featureId = "HideSeen", code = FailureCode.CLASS_NOT_FOUND),
                simulate().copy(featureId = "Others", resolver = null),
            )
        val encoded = FailureReportCodec.encodeAll(reports)
        assertFalse(encoded.contains("4915"))
        assertEquals(reports, FailureReportParser.parseAll(encoded))
    }

    @Test
    fun theHistoryStaysWithinTheStoreCap() {
        assertTrue(FailureReportStore.MAX_REPORTS in 1..200)
    }

    @Test
    fun theHistoryFileNameIsStable() {
        assertEquals("feature-failures.json", FailureReportStore.FILE_NAME)
    }
}
