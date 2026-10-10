package com.wax.module.diagnostics.selftest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * On-device contracts for the parts of #170 that need a real Android runtime.
 *
 * The unit suite proves the engine's logic with fakes. What it cannot prove is
 * the storage behaviour the feature depends on: preferences that survive a
 * restart, a confirmation bound to one WhatsApp build and not another, and a
 * scan that keeps its guarantees when the process is recreated underneath it.
 * Those are what these tests exercise.
 */
@RunWith(AndroidJUnit4::class)
class ExternalVerificationStoreTest {
    @get:Rule
    val perTestTimeout: Timeout = Timeout.seconds(60)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aConfirmationSurvivesTheStoreBeingReopened() {
        val store = ExternalVerificationStore(context)
        store.confirm("typing_privacy", "2.26.39.74", "second account saw nothing", 1_700_000_000_000L)

        // A restart is modelled by a fresh store over the same preference file:
        // a claim that only lived in memory would be worthless after one.
        val reopened = ExternalVerificationStore(context)
        val confirmation = reopened.confirmationFor("typing_privacy", "2.26.39.74")
        assertNotNull("a confirmation must survive a restart", confirmation)
        assertEquals("second account saw nothing", confirmation!!.note)
        assertEquals(1_700_000_000_000L, confirmation.confirmedAtUtcMillis)
    }

    @Test fun aConfirmationIsNotReusedForAnotherWhatsAppBuild() {
        val store = ExternalVerificationStore(context)
        store.confirm("typing_privacy", "2.26.39.74", "observed", 1L)
        assertNull(
            "evidence from one build must not carry to another",
            store.confirmationFor("typing_privacy", "2.26.40.10"),
        )
    }

    @Test fun revokingRemovesTheClaimEntirely() {
        val store = ExternalVerificationStore(context)
        store.confirm("typing_privacy", "2.26.39.74", "observed", 1L)
        store.revoke("typing_privacy", "2.26.39.74")
        assertNull("a withdrawn claim must leave nothing behind", store.confirmationFor("typing_privacy", "2.26.39.74"))
    }

    @Test fun confirmationsAreListedForOneBuildOnly() {
        val store = ExternalVerificationStore(context)
        store.confirm("typing_privacy", "2.26.39.74", "observed", 1L)
        store.confirm("typing_privacy", "2.26.40.10", "observed", 2L)
        assertEquals(1, store.confirmations("2.26.39.74").size)
        assertEquals("typing_privacy", store.confirmations("2.26.39.74").single().featureId)
    }

    @Test fun theEngineKeepsItsGuaranteesAcrossAProcessRestart() {
        val store = ExternalVerificationStore(context)
        store.confirm("typing_privacy", "2.26.39.74", "observed", 1L)
        // The scan runs against a rebuilt store, which is what a cold start does.
        val reopened = ExternalVerificationStore(context)

        val engine = DiagnosticEngine()
        try {
            val definition =
                AtomicCheckInventory
                    .triggerCheck("typing_privacy", "Typing privacy", "hook.typing_privacy")
                    .copy(externalConfirmationRequired = true)
            val report =
                engine.run(
                    DiagnosticEngine.RunConfig.deep("2.26.39.74", "com.whatsapp"),
                    listOf(definition),
                    mapOf(
                        definition.id to
                            DiagnosticEngine.Probe {
                                reopened.confirmationFor("typing_privacy", "2.26.39.74")?.let {
                                    DiagnosticEngine.Observation(
                                        evidence = "confirmed on 2.26.39.74",
                                        level = EvidenceLevel.L5_EXTERNAL,
                                        verification = VerificationState.EXTERNALLY_VERIFIED,
                                    )
                                }
                            },
                    ),
                )
            val result = report.results.single()
            assertEquals(DiagnosticStatus.PASS, result.status)
            assertEquals(EvidenceLevel.L5_EXTERNAL, result.evidenceLevel)
            assertEquals(VerificationState.EXTERNALLY_VERIFIED, result.verification)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun anUnconfirmedTriggerNeverReachesL5() {
        val engine = DiagnosticEngine()
        try {
            val definition =
                AtomicCheckInventory
                    .triggerCheck("typing_privacy", "Typing privacy", "hook.typing_privacy")
                    .copy(externalConfirmationRequired = true)
            // The probe observes an installed hook and nothing more.
            val report =
                engine.run(
                    DiagnosticEngine.RunConfig.deep("2.26.39.74", "com.whatsapp"),
                    listOf(definition),
                    mapOf(
                        definition.id to
                            DiagnosticEngine.Probe {
                                DiagnosticEngine.Observation(
                                    evidence = "INSTALLED",
                                    level = EvidenceLevel.L3_HOOK,
                                    verification = VerificationState.HOOKED,
                                )
                            },
                    ),
                )
            val result = report.results.single()
            assertFalse("an installed hook is not an externally verified behaviour", result.status == DiagnosticStatus.PASS)
            assertEquals(
                DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION,
                result.status,
            )
        } finally {
            engine.shutdown()
        }
    }
}
