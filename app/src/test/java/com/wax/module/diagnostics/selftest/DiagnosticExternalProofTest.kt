package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Test

/** No callback, hook registration or caller-supplied PASS may mimic external proof. */
class DiagnosticExternalProofTest {
    private val check =
        AtomicCheckInventory.featureCheck(
            "hide_read_receipt",
            "Hide read receipt",
            emptyList(),
            "hide_seen",
            externalConfirmationRequired = true,
        )
    private val config = DiagnosticEngine.RunConfig.deep("2.26.synthetic", "com.whatsapp")

    @Test fun triggeredCallbackIsNotProofEvenWithPassOverride() {
        val engine = DiagnosticEngine()
        try {
            val report =
                engine.run(
                    config,
                    listOf(check),
                    mapOf(
                        check.id to
                            DiagnosticEngine.Probe {
                                DiagnosticEngine.Observation(
                                    evidence = "synthetic callback invoked",
                                    level = EvidenceLevel.L4_TRIGGER,
                                    verification = VerificationState.TRIGGERED,
                                    statusOverride = DiagnosticStatus.PASS,
                                )
                            },
                    ),
                )
            assertEquals(
                DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION,
                report.results.single().status,
            )
            assertEquals(0, report.summary.passed)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun explicitlyConfirmedExternalEvidenceMayPass() {
        val engine = DiagnosticEngine()
        try {
            val report =
                engine.run(
                    config,
                    listOf(check),
                    mapOf(
                        check.id to
                            DiagnosticEngine.Probe {
                                DiagnosticEngine.Observation(
                                    evidence = "synthetic user-confirmed peer observation",
                                    level = EvidenceLevel.L5_EXTERNAL,
                                    verification = VerificationState.EXTERNALLY_VERIFIED,
                                )
                            },
                    ),
                )
            assertEquals(DiagnosticStatus.PASS, report.results.single().status)
        } finally {
            engine.shutdown()
        }
    }
}
