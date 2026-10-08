package com.wax.module.xposed.core

import com.wax.module.activation.ActivationHeartbeatFactory
import com.wax.module.activation.ActivationObservation
import com.wax.module.activation.ActivationState
import com.wax.module.activation.ActivationStatusResolver
import com.wax.module.activation.TargetProcessObservation
import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FeatureFailureReport
import com.wax.module.health.HealthFreshness
import com.wax.module.health.HealthReporter
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.RuntimeHealthSnapshot
import com.wax.module.health.RuntimeIdentity
import com.wax.module.health.RuntimeSessions
import com.wax.module.health.RuntimeSubsystem
import com.wax.module.health.SubsystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facts the runtime reports about its own activation, and the aggregate they produce.
 *
 * M01 built a twelve-subsystem model and wired one subsystem to it. This test is about the
 * consequence of that gap: with eleven subsystems reporting nothing, the aggregate is DEGRADED
 * by its own rule 5, so a completely healthy runtime could only ever publish "degraded". That
 * would be the same defect as the boolean it replaced, with more words - so
 * [aHealthyBootstrapProducesReadyAndNotDegraded] is the assertion that keeps the aggregate
 * honest, and it fails the moment a subsystem stops reporting again.
 */
class ActivationProofTest {
    private val sessions = RuntimeSessions(bootId = "boot-1", moduleSessionId = "module-1", targetSessionId = "target-1")

    private fun reporter(): HealthReporter =
        HealthReporter(
            identity =
                RuntimeIdentity(
                    packageName = "com.whatsapp",
                    processName = "com.whatsapp",
                    pid = 4242,
                    moduleVersion = "1.2.0-beta.3",
                    targetVersionName = "2.26.39.78",
                ),
            sessions = sessions,
        )

    private fun report(code: FailureCode) =
        FeatureFailureReport(
            featureId = "Feature[x]",
            code = code,
            moduleVersion = "1.2.0-beta.3",
            whatsappVersion = "2.26.39.78",
            packageName = "com.whatsapp",
        )

    /** The bootstrap the runtime performs, in order. */
    private fun healthyBootstrap(preferencesDirect: Boolean = true): ActivationProof {
        val proof = ActivationProof(reporter())
        proof.frameworkAnswered()
        proof.moduleLoaded("1.2.0-beta.3")
        proof.scopeIsEffective()
        proof.targetProcessIsRunning()
        proof.injected()
        // The engine's own stage is opened directly on the reporter, exactly as FeatureLoader
        // does it. M00 owns that ordering and M00-DEF-02's characterisation test asserts it
        // there, so it is reproduced here rather than moved.
        proof.reporter.succeed(proof.reporter.begin(RuntimeSubsystem.DEXKIT, "resolver.engine.init"), "DexKit initialised")
        proof.preferencesResolved(preferencesDirect)
        proof.coreComponentsReady()
        proof.hooksInstalled()
        proof.optionalFeaturesInstalled(emptyList())
        proof.resolversFinished(emptyList())
        return proof
    }

    @Test
    fun aHealthyBootstrapProducesReadyAndNotDegraded() {
        val proof = healthyBootstrap()

        assertEquals(
            "Every independent subsystem must reach a definitive state in a healthy bootstrap. " +
                "One left UNKNOWN drags the aggregate to DEGRADED by rule 5, which would report a " +
                "working module as reduced-capability on every launch.",
            SubsystemState.READY,
            proof.reporter.snapshot().overallState,
        )
        assertNull(proof.reporter.snapshot().failureCode)
    }

    @Test
    fun theHeartbeatIsProducedFromTheReportedFacts() {
        val heartbeat = healthyBootstrap().heartbeat()

        assertNotNull("Reaching the entry point is already evidence; a heartbeat must exist.", heartbeat)
        assertEquals(SubsystemState.READY, heartbeat?.state)
        assertEquals("com.whatsapp", heartbeat?.packageName)
        assertEquals(4242, heartbeat?.pid)
        assertEquals("boot-1", heartbeat?.bootId)
        assertNull(heartbeat?.failureCode)
        assertEquals(HealthFreshness.FRESH, heartbeat?.freshnessAt(heartbeat!!.timestampMillis))
    }

    @Test
    fun theEffectiveScopeIsProvenByTheFrameworkReachingThisPackage() {
        val proof = ActivationProof(reporter())
        proof.frameworkAnswered()
        proof.moduleLoaded("1.2.0-beta.3")
        proof.scopeIsEffective()

        assertEquals(SubsystemState.READY, proof.reporter.snapshot().scopeState)
    }

    @Test
    fun aPreferenceFallbackIsADegradationRatherThanAFailure() {
        val proof = healthyBootstrap(preferencesDirect = false)
        val snapshot = proof.reporter.snapshot()

        assertEquals(SubsystemState.DEGRADED, snapshot.preferencesState)
        assertEquals(RuntimeFailureCode.PREFERENCES_UNAVAILABLE, snapshot.failureCode)
        assertEquals("The fallback works, so the runtime is degraded rather than broken.", SubsystemState.DEGRADED, snapshot.overallState)
    }

    @Test
    fun aFailedHookSetIsReportedAgainstTheSubsystemThatOwnsIt() {
        val proof = healthyBootstrap()
        proof.essentialHooksFailed("plugins() threw")

        assertEquals(SubsystemState.FAILED, proof.reporter.snapshot().essentialHookState)
        assertEquals(RuntimeFailureCode.ESSENTIAL_HOOK_FAILED, proof.reporter.snapshot().failureCode)
    }

    @Test
    fun optionalFeatureFailuresAreCountedNotGuessed() {
        val proof = healthyBootstrap()
        proof.optionalFeaturesInstalled(listOf(report(FailureCode.CLASS_NOT_FOUND), report(FailureCode.MEMBER_NOT_FOUND)))

        assertEquals(SubsystemState.DEGRADED, proof.reporter.snapshot().optionalHookState)
        assertEquals(RuntimeFailureCode.OPTIONAL_FEATURE_FAILED, proof.reporter.snapshot().failureCode)
    }

    @Test
    fun anInstallationFailureIsNotAbsorbedByTheResolverSubsystem() {
        val proof = healthyBootstrap()
        proof.resolversFinished(listOf(report(FailureCode.HOOK_INSTALL_FAILED)))

        assertEquals(
            "A feature that failed to install is not a resolver failure, and reporting it as one " +
                "is the attribution bug this model exists to prevent.",
            SubsystemState.READY,
            proof.reporter.snapshot().resolverState,
        )
    }

    @Test
    fun aResolverFailureIsReportedAsOne() {
        val proof = healthyBootstrap()
        proof.resolversFinished(listOf(report(FailureCode.RESOLVER_AMBIGUOUS)))

        assertEquals(SubsystemState.DEGRADED, proof.reporter.snapshot().resolverState)
        assertEquals(RuntimeFailureCode.RESOLVER_FAILED, proof.reporter.snapshot().failureCode)
    }

    /**
     * The engine failure reaches the card as itself.
     *
     * The path `FeatureLoader` takes when the engine will not start is reproduced here because
     * the engine itself needs a real dex file and cannot be started in a JVM test. What this
     * asserts is the whole point of the package: an engine failure is named as an engine
     * failure, and can never be presented as the framework being absent.
     */
    @Test
    fun theEngineFailureStaysItsOwnCodeAllTheWayToTheCard() {
        val proof = ActivationProof(reporter())
        proof.frameworkAnswered()
        proof.moduleLoaded("1.2.0-beta.3")
        proof.scopeIsEffective()
        proof.targetProcessIsRunning()
        proof.injected()
        val reporter = proof.reporter
        val stage = reporter.begin(RuntimeSubsystem.DEXKIT, "resolver.engine.init")
        reporter.fail(stage, RuntimeFailureCode.DEXKIT_INIT_FAILED, "initWithPath returned false")

        val heartbeat = proof.heartbeat()
        assertEquals(RuntimeFailureCode.DEXKIT_INIT_FAILED, heartbeat?.failureCode)

        val status =
            ActivationStatusResolver.resolve(
                ActivationObservation("com.whatsapp", true, TargetProcessObservation.RUNNING, heartbeat, false),
                heartbeat!!.timestampMillis,
                "boot-1",
            )

        assertEquals(ActivationState.FAILED, status.state)
        assertEquals(RuntimeFailureCode.DEXKIT_INIT_FAILED, status.failureCode)
        assertFalse(
            "This is the defect M00 recorded: an engine failure presented as the framework being " +
                "absent. The card must never be allowed to say it.",
            status.mayReportFrameworkActivationFailure,
        )
    }

    @Test
    fun nothingReportedProducesNoHeartbeat() {
        assertNull(
            "A reporter that has been told nothing cannot produce evidence, and publishing a " +
                "placeholder would put 'something happened' on the wire as though it meant something.",
            ActivationProof(reporter()).heartbeat(),
        )
    }

    @Test
    fun theCurrentStageIsTheLastStageThatStarted() {
        val proof = ActivationProof(reporter())
        assertEquals(ActivationHeartbeatFactory.STAGE_ENTRY, proof.currentStage())

        proof.frameworkAnswered()
        assertEquals("framework.entry", proof.currentStage())

        proof.moduleLoaded("1.2.0-beta.3")
        assertEquals("module.load", proof.currentStage())
    }

    @Test
    fun onlyResolverClassifiedReportsAreResolverFailures() {
        assertTrue(report(FailureCode.RESOLVER_INIT_FAILED).isResolverFailure())
        assertTrue(report(FailureCode.RESOLVER_AMBIGUOUS).isResolverFailure())
        assertTrue(report(FailureCode.RESOLVER_NOT_FOUND).isResolverFailure())
        assertFalse(report(FailureCode.HOOK_INSTALL_FAILED).isResolverFailure())
        assertFalse(report(FailureCode.PREFERENCE_ERROR).isResolverFailure())
        assertFalse(report(FailureCode.UNEXPECTED).isResolverFailure())
    }

    @Test
    fun aSnapshotWithNothingInItProducesNoHeartbeat() {
        val snapshot =
            RuntimeHealthSnapshot.of(
                sessions = sessions,
                timestampMillis = 1L,
                packageName = "com.whatsapp",
                processName = "com.whatsapp",
                pid = 1,
                moduleVersion = "1.2.0-beta.3",
                states = emptyMap(),
            )

        assertNull(ActivationHeartbeatFactory.from(snapshot))
    }
}
