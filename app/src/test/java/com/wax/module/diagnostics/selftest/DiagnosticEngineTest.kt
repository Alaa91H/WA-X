package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome mapping and false-PASS injection (#170 acceptance gate 3):
 * an installed hook without callback or remote verification must never be
 * reported as functional.
 */
class DiagnosticEngineTest {
    private fun config(mode: DiagnosticEngine.RunConfig.Mode = DiagnosticEngine.RunConfig.Mode.DEEP_SCAN) =
        DiagnosticEngine.RunConfig(mode, 2_000L, "2.26.39.74", "com.whatsapp")

    private fun engine() = DiagnosticEngine()

    @Test fun anInstalledHookAloneIsNotFunctional() {
        val definition =
            AtomicCheckInventory.featureCheck(
                "hide_blue_tick", "Hide blue tick", listOf(AtomicCheckInventory.JID_RAW_STRING),
                "hide_seen", externalConfirmationRequired = true,
            )
        val engine = engine()
        val report =
            engine.run(
                config(),
                listOf(definition),
                mapOf(
                    definition.id to DiagnosticEngine.Probe {
                        // Registration only: no invocation, no external observation.
                        DiagnosticEngine.Observation(
                            evidence = "hook registered",
                            level = EvidenceLevel.L3_HOOK,
                            verification = VerificationState.HOOKED,
                        )
                    },
                ),
            )
        val result = report.results.single()
        assertNotEquals(
            "a registered hook must not be reported as working",
            DiagnosticStatus.PASS,
            result.status,
        )
        assertEquals(VerificationState.HOOKED, result.verification)
        engine.shutdown()
    }

    @Test fun aPassWithoutAnyObservationIsDowngradedToNotTested() {
        val result =
            AtomicCheckResult(
                id = "x", title = "x", scope = "s",
                status = DiagnosticStatus.PASS,
                evidenceLevel = EvidenceLevel.L3_HOOK,
                expected = "e", observedEvidence = "", verification = VerificationState.HOOKED,
                timestampMillis = 0, whatsappBuild = "b", severity = "high",
                confidence = 1.0, failureClass = FailureClass.NONE, remediation = "r",
            )
        assertEquals(
            DiagnosticStatus.NOT_TESTED,
            result.honestStatus(mapOf("x" to result)),
        )
    }

    @Test fun aPassBehindAFailedDependencyBecomesBlocked() {
        val root =
            AtomicCheckResult(
                id = "root", title = "root", scope = "resolver",
                status = DiagnosticStatus.FAIL, evidenceLevel = EvidenceLevel.L2_RESOLVER,
                expected = "e", observedEvidence = "missing", verification = VerificationState.NOT_OBSERVED,
                timestampMillis = 0, whatsappBuild = "b", severity = "high", confidence = 1.0,
                failureClass = FailureClass.DEPENDENCY_MISSING, remediation = "fix root",
            )
        val dependent =
            AtomicCheckResult(
                id = "dependent", title = "dependent", scope = "feature",
                status = DiagnosticStatus.PASS, evidenceLevel = EvidenceLevel.L3_HOOK,
                expected = "e", observedEvidence = "installed",
                verification = VerificationState.HOOKED, timestampMillis = 0, whatsappBuild = "b",
                severity = "high", confidence = 1.0, failureClass = FailureClass.NONE,
                remediation = "", dependsOn = listOf("root"),
            )
        assertEquals(
            DiagnosticStatus.BLOCKED,
            dependent.honestStatus(mapOf("root" to root, "dependent" to dependent)),
        )
    }

    @Test fun aPassBehindAnUnverifiedDependencyIsAlsoBlocked() {
        // "We did not measure the resolver" is not "the resolver works".
        val root =
            AtomicCheckResult(
                id = "root", title = "root", scope = "resolver",
                status = DiagnosticStatus.NOT_TESTED, evidenceLevel = EvidenceLevel.L2_RESOLVER,
                expected = "e", observedEvidence = "no observation",
                verification = VerificationState.NOT_OBSERVED,
                timestampMillis = 0, whatsappBuild = "b", severity = "high", confidence = 1.0,
                failureClass = FailureClass.NONE, remediation = "",
            )
        val dependent =
            AtomicCheckResult(
                id = "dependent", title = "dependent", scope = "feature",
                status = DiagnosticStatus.PASS, evidenceLevel = EvidenceLevel.L3_HOOK,
                expected = "e", observedEvidence = "installed",
                verification = VerificationState.HOOKED, timestampMillis = 0, whatsappBuild = "b",
                severity = "high", confidence = 1.0, failureClass = FailureClass.NONE,
                remediation = "", dependsOn = listOf("root"),
            )
        assertEquals(
            DiagnosticStatus.BLOCKED,
            dependent.honestStatus(mapOf("root" to root)),
        )
    }

    @Test fun aMissingProbeIsNotTestedRatherThanPassed() {
        val definition = AtomicCheckInventory.byId(AtomicCheckInventory.HEARTBEAT)!!
        val engine = engine()
        val report = engine.run(config(), listOf(definition), emptyMap())
        assertEquals(DiagnosticStatus.NOT_TESTED, report.results.single().status)
        assertTrue(report.results.single().observedEvidence.isNotBlank())
        engine.shutdown()
    }

    @Test fun aTimeoutFailsRatherThanHangs() {
        val definition = AtomicCheckInventory.byId(AtomicCheckInventory.HEARTBEAT)!!
        val engine = DiagnosticEngine()
        val report =
            engine.run(
                DiagnosticEngine.RunConfig(
                    DiagnosticEngine.RunConfig.Mode.DEEP_SCAN, 150L, "b", "s",
                ),
                listOf(definition),
                mapOf(definition.id to DiagnosticEngine.Probe { Thread.sleep(5_000L); null }),
            )
        val result = report.results.single()
        assertEquals(DiagnosticStatus.FAIL, result.status)
        assertEquals(FailureClass.TIMEOUT, result.failureClass)
        engine.shutdown()
    }

    @Test fun aPreferenceDisabledFeatureIsNotAFailedHook() {
        val definition =
            AtomicCheckInventory.featureCheck(
                "disabled_feature", "Disabled feature",
                listOf(AtomicCheckInventory.JID_RAW_STRING), "off_key",
                externalConfirmationRequired = false,
            )
        val engine = engine()
        val report =
            engine.run(
                config(),
                listOf(definition),
                mapOf(
                    definition.id to DiagnosticEngine.Probe {
                        DiagnosticEngine.Observation(
                            evidence = "preference off; hook intentionally absent",
                            level = EvidenceLevel.L2_RESOLVER,
                            verification = VerificationState.NOT_OBSERVED,
                            expectedMatch = false,
                            failureClass = FailureClass.PREFERENCE_DISABLED,
                        )
                    },
                ),
            )
        val result = report.results.single()
        assertEquals(FailureClass.PREFERENCE_DISABLED, result.failureClass)
        assertNotEquals(
            "a disabled feature is not a broken hook",
            FailureClass.DEPENDENCY_MISSING,
            result.failureClass,
        )
        engine.shutdown()
    }

    @Test fun quickCheckStaysCheapAndNeverRunsAFullDexScan() {
        val engine = engine()
        val expensive = AtomicCheckInventory.byId(AtomicCheckInventory.MESSAGE_CLASS)!!
        var expensiveRan = false
        val report =
            engine.run(
                DiagnosticEngine.RunConfig.quick("b", "s"),
                AtomicCheckInventory.PIPELINE,
                mapOf(
                    expensive.id to DiagnosticEngine.Probe {
                        expensiveRan = true
                        DiagnosticEngine.Observation("ok", EvidenceLevel.L2_RESOLVER)
                    },
                ),
            )
        assertFalse("quick mode must not run the message-class resolver", expensiveRan)
        assertTrue(report.results.none { it.id == expensive.id })
        engine.shutdown()
    }

    @Test fun cancellationStopsFurtherChecks() {
        val engine = engine()
        var executed = 0
        val report =
            engine.run(
                config(),
                AtomicCheckInventory.PIPELINE,
                AtomicCheckInventory.PIPELINE.associate { definition ->
                    definition.id to DiagnosticEngine.Probe {
                        executed++
                        DiagnosticEngine.Observation("ok", EvidenceLevel.L0_PACKAGE)
                    }
                },
                onProgress = { _, _, _ -> engine.cancel() },
            )
        assertTrue("cancellation must stop the run", executed < AtomicCheckInventory.PIPELINE.size)
        assertTrue(report.results.size <= executed)
        engine.shutdown()
    }

    @Test fun progressCountsVerifiedChecksAndNeverInventsAPercentage() {
        val engine = engine()
        val seen = mutableListOf<Pair<Int, Int>>()
        engine.run(
            config(),
            AtomicCheckInventory.PIPELINE.take(4),
            emptyMap(),
            onProgress = { completed, total, _ -> seen.add(completed to total) },
        )
        assertEquals(4, seen.size)
        assertEquals(listOf(1, 2, 3, 4), seen.map { it.first })
        assertTrue(seen.all { it.second == 4 })
        engine.shutdown()
    }

    @Test fun everyCheckThatRunsIsReported() {
        // A scan that silently dropped results would look like a clean bill of
        // health; the report must contain exactly what was asked for.
        val engine = engine()
        val definitions =
            listOf(
                AtomicCheckInventory.byId(AtomicCheckInventory.ENV_ANDROID)!!,
                AtomicCheckInventory.byId(AtomicCheckInventory.ENV_SCOPE)!!,
                AtomicCheckInventory.byId(AtomicCheckInventory.HEARTBEAT)!!,
            )
        val report = engine.run(config(), definitions, emptyMap())
        assertEquals(definitions.map { it.id }.sorted(), report.results.map { it.id }.sorted())
        engine.shutdown()
    }

    @Test fun theQuickSubsetLeavesOutTheResolverChain() {
        // Quick Check may load the DexKit library, but it must not walk the DEX
        // to resolve the contact/JID chain, which is what makes it slow.
        val quick = engine().quickSubset().map { it.id }
        assertTrue("quick mode must be a strict subset", quick.size < AtomicCheckInventory.all().size)
        for (resolverHeavy in listOf(
            AtomicCheckInventory.CONTACT_CLASS,
            AtomicCheckInventory.CONTACT_DATA_CLASS,
            AtomicCheckInventory.JID_CLASS,
            AtomicCheckInventory.JID_RAW_STRING,
        )) {
            assertTrue("$resolverHeavy must not run in the cheap prefix", quick.none { it == resolverHeavy })
        }
    }
}
