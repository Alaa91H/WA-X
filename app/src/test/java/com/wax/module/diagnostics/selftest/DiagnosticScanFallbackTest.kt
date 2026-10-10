package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticScanFallbackTest {
    private val config = DiagnosticEngine.RunConfig.quick("2.26.test", "com.whatsapp")

    @Test fun noScanReportNeverInventsPassingChecks() {
        val report = DiagnosticScanFallback.unrun(config, 12345L)
        assertTrue(report.results.isEmpty())
        assertEquals(0, report.summary.total)
        assertEquals(0, report.summary.passed)
        assertTrue(report.scanId.startsWith("not-run-"))
        val bytes =
            DiagnosticZipExporter().build(
                DiagnosticReportBuilder.entries(
                    DiagnosticReportBuilder.Inputs(
                        report,
                        DiagnosticReportBuilder.Environment("1", "abc", 1L, "com.whatsapp", "2.26.test", "17", 37, "arm64"),
                        emptyList(),
                        emptyMap(),
                        null,
                    ),
                ),
            )
        assertTrue(DiagnosticZipExporter().verify(bytes.bytes).valid)
    }

    @Test fun failureReportRecordsRealErrorWithoutPersonalContent() {
        val report = DiagnosticScanFallback.failed(config, "IllegalStateException", 23456L)
        assertEquals(1, report.summary.failed)
        assertEquals(0, report.summary.passed)
        assertEquals(DiagnosticStatus.FAIL, report.results.single().status)
        assertEquals(FailureClass.CRASHED, report.results.single().failureClass)
        assertTrue(
            report.results
                .single()
                .observedEvidence
                .contains("IllegalStateException"),
        )
        assertFalse(
            report.results
                .single()
                .observedEvidence
                .contains("contact@"),
        )
    }
}
