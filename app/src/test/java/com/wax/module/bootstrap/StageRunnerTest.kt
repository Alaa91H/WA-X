package com.wax.module.bootstrap

import com.wax.module.health.HealthReporter
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
 * The three properties M03's gate names, as tests.
 *
 * > DexKit failure is diagnosable, optional failure cannot block READY, and bootstrap is
 * > deterministic/idempotent.
 *
 * Each section below is one of those three, and the tests are written against the runner rather
 * than against `FeatureLoader` because the runner is where the policy lives: containment, skip
 * propagation, idempotency and the criticality table are all properties of
 * [StageRunner], and testing them through a class that needs an `Application` and a class loader
 * would prove that one wiring works rather than that the rules hold.
 */
class StageRunnerTest {
    private val sessions = RuntimeSessions(bootId = "boot-1", moduleSessionId = "module-1", targetSessionId = "target-1")

    private fun reporter(): HealthReporter =
        HealthReporter(
            identity =
                RuntimeIdentity(
                    packageName = "com.whatsapp",
                    processName = "com.whatsapp",
                    pid = 4242,
                    moduleVersion = "1.2.0-beta.4",
                    targetVersionName = "2.26.39.78",
                ),
            sessions = sessions,
        )

    /** Every stage succeeds unless a test says otherwise. */
    private fun outcomes(
        failing: Set<BootstrapStage> = emptySet(),
        degraded: Set<BootstrapStage> = emptySet(),
    ): (BootstrapStage) -> StageOutcome =
        { stage ->
            when (stage) {
                in failing -> StageOutcome.FAILED
                in degraded -> StageOutcome.DEGRADED
                else -> StageOutcome.SUCCEEDED
            }
        }

    private fun runner(reporter: HealthReporter = reporter()): StageRunner = StageRunner(reporter) { reporterClock }

    private var reporterClock: Long = 0L

    // ------------------------------------------------------------------ the classification

    /**
     * A CORE stage without a justification is refused.
     *
     * CORE is the only classification that stops the runtime, so it is the only one that has to
     * argue for itself. Without this the classification becomes a label somebody adds when a
     * stage is annoying, and the argument is never made.
     */
    @Test
    fun aCoreStageWithoutAJustificationIsRefused() {
        val unjustified =
            BootstrapStage.entries.filter { it.criticality == StageCriticality.CORE && it.justification.isNullOrBlank() }

        assertTrue(
            "Every CORE stage must say why nothing below it can work without it: " +
                unjustified.joinToString { it.name },
            unjustified.isEmpty(),
        )
    }

    /** A stage that is not CORE carries no justification, so the field stays meaningful. */
    @Test
    fun aStageThatIsNotCoreCarriesNoJustification() {
        val overclaimed =
            BootstrapStage.entries.filter {
                it.criticality != StageCriticality.CORE && !it.justification.isNullOrBlank()
            }

        assertTrue(
            "A justification on a stage that cannot stop anything is a comment pretending to be " +
                "an argument: ${overclaimed.joinToString { it.name }}",
            overclaimed.isEmpty(),
        )
    }

    /**
     * Every subsystem is written by exactly one stage.
     *
     * Two stages writing one subsystem is how that subsystem's state becomes a function of which
     * writer ran last, and the aggregate the Manager reads would then depend on stage order.
     */
    @Test
    fun everySubsystemIsWrittenByExactlyOneStage() {
        val owners =
            BootstrapStage.entries
                .mapNotNull { stage -> stage.subsystem?.let { it to stage } }
                .groupBy({ it.first }, { it.second })

        val duplicated = owners.filterValues { it.size > 1 }
        assertTrue(
            "These subsystems are written by more than one stage: ${duplicated.mapValues { it.value.map { stage -> stage.name } }}",
            duplicated.isEmpty(),
        )
        assertEquals(
            "Every independent subsystem must be covered, or the aggregate can never be READY.",
            RuntimeSubsystem.independent.toSet(),
            owners.keys,
        )
    }

    /** The stage order is the issue's order. */
    @Test
    fun theStageOrderIsTheOneThePhaseDefines() {
        val expected =
            listOf(
                BootstrapStage.FRAMEWORK,
                BootstrapStage.MODULE,
                BootstrapStage.SCOPE,
                BootstrapStage.TARGET,
                BootstrapStage.INJECTION,
                BootstrapStage.PREFERENCES,
                BootstrapStage.APPLICATION_ATTACH,
                BootstrapStage.DEX_ENGINE,
                BootstrapStage.RESOLVER_CACHE,
                BootstrapStage.CORE,
                BootstrapStage.ESSENTIAL,
                BootstrapStage.OPTIONAL,
                BootstrapStage.RUNTIME_VERIFICATION,
                BootstrapStage.READY,
            )

        assertEquals(expected, BootstrapStage.ORDER)
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun stagesRunInDeclarationOrder() {
        val seen = mutableListOf<BootstrapStage>()
        val runner = runner()

        runner.run(execute = { stage ->
            seen += stage
            StageOutcome.SUCCEEDED
        }, isApplicationAvailable = { true })

        assertEquals(BootstrapStage.ORDER, seen)
    }

    @Test
    fun twoIdenticalRunsProduceIdenticalReports() {
        val first = runner().run(outcomes(), isApplicationAvailable = { true })
        val second = runner().run(outcomes(), isApplicationAvailable = { true })

        assertEquals(first.results.map { it.stage to it.outcome }, second.results.map { it.stage to it.outcome })
        assertEquals(first.state, second.state)
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun aSecondPassRunsNothingThatAlreadyFinished() {
        var executions = 0
        val runner = runner()
        val execute: (BootstrapStage) -> StageOutcome = {
            executions++
            StageOutcome.SUCCEEDED
        }

        runner.run(execute = execute, isApplicationAvailable = { true })
        val afterFirst = executions
        runner.run(execute = execute, isApplicationAvailable = { true })

        assertEquals("A terminal stage must never run twice.", afterFirst, executions)
    }

    /** The first pass cannot run the stages that need the Application, and must not fail them. */
    @Test
    fun aStageThatCannotRunYetIsAwaitingRatherThanFailed() {
        val runner = runner()

        val report = runner.run(outcomes(), isApplicationAvailable = { false })

        val awaiting = report.awaiting().map { it.stage }
        assertEquals(
            BootstrapStage.entries.filter { it.needsApplication }.toSet(),
            awaiting.toSet(),
        )
        assertTrue(
            "A process that has not finished starting is not a failed process.",
            report.failed().isEmpty(),
        )
        assertEquals(BootstrapState.IN_PROGRESS, report.state)
    }

    /** The second pass picks up exactly where the first stopped. */
    @Test
    fun theSecondPassContinuesWhereTheFirstStopped() {
        val runner = runner()
        val seen = mutableListOf<BootstrapStage>()

        runner.run(execute = { stage ->
            seen += stage
            StageOutcome.SUCCEEDED
        }, isApplicationAvailable = { false })
        val firstPassCount = seen.size
        val report =
            runner.run(execute = { stage ->
                seen += stage
                StageOutcome.SUCCEEDED
            }, isApplicationAvailable = { true })

        assertTrue("The first pass should have done some work.", firstPassCount > 0)
        assertEquals(
            "Every stage runs exactly once across both passes.",
            BootstrapStage.ORDER.size,
            seen.size,
        )
        assertEquals(BootstrapState.READY, report.state)
        assertEquals(2, report.pass)
    }

    // ------------------------------------------------------------------ the engine failure

    /**
     * The gate's first clause: a DexKit failure is diagnosable.
     *
     * The engine failing is the case M00 recorded and M01 half-fixed. What it must never do is
     * stop the whole bootstrap with nothing said - so the stages that do not need it still run,
     * the ones that do are skipped *with a reason*, and the subsystem states name it.
     */
    @Test
    fun anEngineFailureSkipsOnlyWhatNeedsTheEngine() {
        val reporter = reporter()
        val report =
            runner(reporter).run(
                execute = outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)),
                isApplicationAvailable = { true },
            )

        val skipped = report.skipped().map { it.stage }
        assertEquals(
            "Only the stages that depend on the engine may be skipped: $skipped",
            setOf(
                BootstrapStage.RESOLVER_CACHE,
                BootstrapStage.CORE,
                BootstrapStage.ESSENTIAL,
                BootstrapStage.OPTIONAL,
                BootstrapStage.RUNTIME_VERIFICATION,
                BootstrapStage.READY,
            ),
            skipped.toSet(),
        )
        assertEquals(
            BootstrapState.FAILED,
            report.state,
        )
        assertEquals(
            com.wax.module.health.RuntimeFailureCode.DEXKIT_INIT_FAILED,
            report.byStage.getValue(BootstrapStage.DEX_ENGINE).failureCode,
        )
        assertEquals(
            SubsystemState.FAILED,
            reporter.snapshot().dexKitState,
        )
        assertTrue(
            "The stages that do not need the engine must still have run: " +
                report.results.filter { it.outcome != StageOutcome.SKIPPED }.map { it.stage.name },
            report.results.any { it.stage == BootstrapStage.APPLICATION_ATTACH && it.outcome == StageOutcome.SUCCEEDED },
        )
    }

    @Test
    fun aSkippedStageNamesTheStageThatBlockedIt() {
        val report =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)),
                isApplicationAvailable = { true },
            )

        assertEquals(
            listOf(BootstrapStage.DEX_ENGINE),
            report.byStage.getValue(BootstrapStage.ESSENTIAL).skippedBecause,
        )
    }

    /**
     * An essential failure skips the optional set, with the reason named.
     *
     * The asymmetry with [anOptionalFailureCannotBlockReady] is deliberate and is the isolation
     * this phase is for: optional features are built on the hook set, so running them after it
     * failed produces a cascade of noise for a consequence of the first failure. The reverse -
     * an optional failure stopping anything - never happens.
     */
    @Test
    fun anEssentialFailureSkipsWhatIsBuiltOnItAndAnOptionalOneNeverDoes() {
        val essentialFailed =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.ESSENTIAL)),
                isApplicationAvailable = { true },
            )
        val optionalFailed =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.OPTIONAL)),
                isApplicationAvailable = { true },
            )

        assertEquals(
            StageOutcome.SKIPPED,
            essentialFailed.byStage.getValue(BootstrapStage.OPTIONAL).outcome,
        )
        assertEquals(
            listOf(BootstrapStage.ESSENTIAL),
            essentialFailed.byStage.getValue(BootstrapStage.OPTIONAL).skippedBecause,
        )
        assertEquals(
            "An optional failure skips nothing at all.",
            emptyList<BootstrapStage>(),
            optionalFailed.byStage.getValue(BootstrapStage.RUNTIME_VERIFICATION).skippedBecause,
        )
        assertTrue(optionalFailed.skipped().isEmpty())
    }

    // ------------------------------------------------------------------ the optional failure

    /** The gate's second clause: an optional failure cannot block READY. */
    @Test
    fun anOptionalFailureCannotBlockReady() {
        val report =
            runner().run(
                execute = outcomes(degraded = setOf(BootstrapStage.OPTIONAL)),
                isApplicationAvailable = { true },
            )

        assertEquals(BootstrapState.DEGRADED, report.state)
        assertTrue(
            "An optional failure must never make the runtime unusable.",
            report.isReady,
        )
        assertTrue(
            "An optional failure must not skip anything.",
            report.skipped().isEmpty(),
        )
        assertTrue(report.failed().isEmpty())
        assertEquals(
            "Every stage that is not optional still succeeded, so the runtime did everything it had to.",
            BootstrapStage.entries.count { it.criticality != StageCriticality.OPTIONAL },
            report.results.count { it.stage.criticality != StageCriticality.OPTIONAL && it.outcome == StageOutcome.SUCCEEDED },
        )
    }

    /** The same stage classified CORE would fail the bootstrap - which is why the classification matters. */
    @Test
    fun theSameStageClassifiedCoreFailsTheBootstrap() {
        val report =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)),
                isApplicationAvailable = { true },
            )

        assertFalse(report.isReady)
        assertEquals(BootstrapState.FAILED, report.state)
    }

    // ------------------------------------------------------------------ containment

    @Test
    fun aStageThatThrowsIsRecordedAndDoesNotStopTheSequence() {
        val runner = runner()
        val report =
            runner.run(
                execute = { stage ->
                    if (stage == BootstrapStage.CORE) throw IllegalStateException("component refused") else StageOutcome.SUCCEEDED
                },
                isApplicationAvailable = { true },
            )

        assertEquals(StageOutcome.FAILED, report.byStage.getValue(BootstrapStage.CORE).outcome)
        assertEquals(
            "The throwable is recorded against the stage that owns it, so the card names a " +
                "component rather than the runtime as a whole.",
            com.wax.module.health.RuntimeFailureCode.CORE_COMPONENT_FAILED,
            report.byStage.getValue(BootstrapStage.CORE).failureCode,
        )
        assertEquals(
            "The skip names the cause, not the stage it merely happened to follow.",
            BootstrapStage.CORE,
            report.byStage
                .getValue(BootstrapStage.ESSENTIAL)
                .skippedBecause
                .single(),
        )
    }

    @Test
    fun aContainedFailureIsStillLogged() {
        val logged = mutableListOf<BootstrapStage>()
        val previous = BootstrapLogSink.sink
        try {
            BootstrapLogSink.sink = BootstrapLogSink { stage, _ -> logged += stage }
            runner().run(
                execute = { stage ->
                    if (stage == BootstrapStage.RESOLVER_CACHE) throw IllegalStateException("boom") else StageOutcome.SUCCEEDED
                },
                isApplicationAvailable = { true },
            )
        } finally {
            BootstrapLogSink.sink = previous
        }

        assertEquals(
            "Containment that logs nothing is indistinguishable from a swallowed failure.",
            listOf(BootstrapStage.RESOLVER_CACHE),
            logged,
        )
    }

    /** A degraded dependency is not a blocking one. */
    @Test
    fun aDegradedStageDoesNotSkipWhatDependsOnIt() {
        val report =
            runner().run(
                execute = outcomes(degraded = setOf(BootstrapStage.PREFERENCES)),
                isApplicationAvailable = { true },
            )

        assertTrue(
            "Skipping everything downstream of a degradation is how one lost capability turns " +
                "into no runtime at all.",
            report.skipped().isEmpty(),
        )
        assertEquals(BootstrapState.DEGRADED, report.state)
    }

    // ------------------------------------------------------------------ the health record

    /**
     * Every subsystem reaches a definitive state in a healthy bootstrap.
     *
     * This is the assertion M02 added against a model wired for one subsystem, and the reason a
     * stage list exists: with a subsystem left reporting nothing, the aggregate is DEGRADED by
     * rule 5 and a working module is described as reduced-capability on every launch.
     */
    @Test
    fun aHealthyBootstrapProducesReadyAndNotDegraded() {
        val reporter = reporter()

        runner(reporter).run(outcomes(), isApplicationAvailable = { true })

        assertEquals(
            "One subsystem left UNKNOWN drags the aggregate to DEGRADED, and that is the same " +
                "false banner as the boolean this program removed.",
            SubsystemState.READY,
            reporter.snapshot().overallState,
        )
        assertNull(reporter.snapshot().failureCode)
    }

    @Test
    fun anEngineFailureReachesTheHealthAggregateAsTheEngine() {
        val reporter = reporter()

        runner(reporter).run(outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)), isApplicationAvailable = { true })

        assertEquals(SubsystemState.FAILED, reporter.snapshot().dexKitState)
        assertEquals(
            com.wax.module.health.RuntimeFailureCode.DEXKIT_INIT_FAILED,
            reporter.snapshot().failureCode,
        )
    }

    @Test
    fun aSkippedStageIsRecordedAsSkippedRatherThanAsWorking() {
        val reporter = reporter()

        runner(reporter).run(outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)), isApplicationAvailable = { true })

        assertEquals(SubsystemState.SKIPPED, reporter.snapshot().resolverState)
        assertNotNull(
            "The subsystem state has to distinguish 'not attempted' from 'working'; reporting " +
                "SKIPPED as READY is the claim this phase exists to prevent.",
            reporter.snapshot().stateOf(RuntimeSubsystem.RESOLVER),
        )
    }

    // ------------------------------------------------------------------ timing

    @Test
    fun everyStageReportsHowLongItTook() {
        var clock = 0L
        val runner = StageRunner(reporter()) { clock }
        // Each stage costs a different amount, so "slowest" is a fact rather than a tie.
        val costs = BootstrapStage.ORDER.withIndex().associate { (index, stage) -> stage to (index + 1) * 10 }
        val report =
            runner.run(
                execute = { stage ->
                    clock += costs.getValue(stage)
                    StageOutcome.SUCCEEDED
                },
                isApplicationAvailable = { true },
            )

        assertEquals(BootstrapStage.ORDER.size, report.results.size)
        assertTrue(
            "Every attempted stage must report a duration.",
            report.results.all { it.durationMillis == costs.getValue(it.stage).toLong() },
        )
        assertEquals(
            BootstrapStage.READY,
            report.slowestStage?.stage,
        )
        assertEquals(
            "The total has to be measured, not assumed from the sum of the parts.",
            costs.values.sum().toLong(),
            report.totalMillis,
        )
        assertEquals(report.results.size, report.timings().size)
    }

    @Test
    fun aSummaryNamesTheStagesRatherThanTheProcess() {
        val report =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)),
                isApplicationAvailable = { true },
            )

        assertTrue(
            "A summary that says 'startup failed' cannot be acted on. Got: ${report.summary()}",
            report.summary().contains("DEX_ENGINE"),
        )
        assertTrue(report.summary().contains("DEXKIT_INIT_FAILED"))
    }

    // ------------------------------------------------------------------ record integrity

    @Test
    fun aResultCannotClaimAFailureWithoutACode() {
        val failure =
            runCatching { ComponentResult(BootstrapStage.DEX_ENGINE, StageOutcome.FAILED) }

        assertTrue(
            "A failed stage with no code is the exact shape of 'LSPosed is disabled': a failure " +
                "with nothing to name it by.",
            failure.isFailure,
        )
    }

    @Test
    fun aResultCannotCarryAnotherSubsystemCode() {
        val failure =
            runCatching {
                ComponentResult(
                    BootstrapStage.OPTIONAL,
                    StageOutcome.FAILED,
                    com.wax.module.health.RuntimeFailureCode.ESSENTIAL_HOOK_FAILED,
                )
            }

        assertTrue(
            "Attributing one subsystem's failure to another is the mistake the whole model " +
                "exists to prevent.",
            failure.isFailure,
        )
    }

    @Test
    fun aReportIsSelfConsistent() {
        val report =
            runner().run(
                execute = outcomes(failing = setOf(BootstrapStage.DEX_ENGINE)),
                isApplicationAvailable = { true },
            )

        assertTrue(
            "A stage cannot be skipped because of something that did not fail: " + report.results,
            report.isSelfConsistent,
        )
    }
}
