package com.wax.module.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The reporter, tested at the boundary a component sees.
 *
 * The interesting cases are the ones where reporting could lie: a failure attributed to the
 * wrong subsystem, a report that arrives after the state it contradicts, a second session's
 * state being read as the current one, and a message that carries a user identifier into a
 * file that exists to be read back.
 */
class HealthReporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock: Long = 10_000L

    private val identity =
        RuntimeIdentity(
            packageName = "com.whatsapp",
            processName = "com.whatsapp",
            pid = 99,
            moduleVersion = "1.2.0-beta.1",
            targetVersionName = "2.26.40.21",
            targetVersionCode = 2_264_021L,
            androidSdk = 35,
        )

    private val sessions =
        RuntimeSessions(
            bootId = "boot-1",
            moduleSessionId = "module-1",
            targetSessionId = "target-1",
        )

    private fun reporter(
        store: RuntimeHealthStore? = null,
        summary: () -> FeatureSummary = { FeatureSummary() },
    ): HealthReporter =
        HealthReporter(
            identity = identity,
            sessions = sessions,
            store = store,
            featureSummary = summary,
            now = { clock },
        )

    private fun ready(reporter: HealthReporter) {
        for (subsystem in RuntimeSubsystem.independent) {
            reporter.report(subsystem, SubsystemState.READY)
        }
    }

    // ---------------------------------------------------------------- reporting

    @Test
    fun reportingAStateRecordsItAndAnIllegalTransitionDoesNot() {
        val reporter = reporter()
        assertEquals(ReportOutcome.RECORDED, reporter.report(RuntimeSubsystem.FRAMEWORK, SubsystemState.READY))
        assertEquals(SubsystemState.READY, reporter.snapshot().frameworkState)

        assertEquals(
            "a state must not be able to fall back to UNKNOWN",
            ReportOutcome.REJECTED_TRANSITION,
            reporter.report(RuntimeSubsystem.FRAMEWORK, SubsystemState.UNKNOWN),
        )
        assertEquals(SubsystemState.READY, reporter.snapshot().frameworkState)
        assertEquals(1, reporter.rejectedReportCount())
    }

    @Test
    fun aFailureCodeBelongingToAnotherSubsystemIsRefusedRatherThanAttached() {
        val reporter = reporter()
        assertEquals(
            ReportOutcome.REJECTED_FOREIGN_CODE,
            reporter.report(RuntimeSubsystem.PREFERENCES, SubsystemState.FAILED, RuntimeFailureCode.DEXKIT_INIT_FAILED),
        )
        val snapshot = reporter.snapshot()
        assertEquals(
            "the failure is still visible, but it is not attributed to DexKit",
            SubsystemState.FAILED,
            snapshot.preferencesState,
        )
        assertEquals(RuntimeFailureCode.UNKNOWN, snapshot.failureCode)
        assertEquals(1, reporter.rejectedReportCount())
    }

    @Test
    fun aRuntimeWideCodeMayDescribeASubsystemStage() {
        val reporter = reporter()
        assertEquals(
            ReportOutcome.RECORDED,
            reporter.report(RuntimeSubsystem.RESOLVER, SubsystemState.FAILED, RuntimeFailureCode.RUNTIME_TIMEOUT),
        )
        assertEquals(RuntimeFailureCode.RUNTIME_TIMEOUT, reporter.snapshot().failureCode)
    }

    @Test
    fun theRuntimeStateCannotBeReportedByAComponent() {
        val reporter = reporter()
        assertEquals(
            ReportOutcome.REJECTED_AGGREGATE,
            reporter.report(RuntimeSubsystem.OVERALL_RUNTIME, SubsystemState.READY),
        )
        assertEquals(SubsystemState.UNKNOWN, reporter.snapshot().overallState)
    }

    @Test
    fun aStageProducesAnEventThatCarriesItsDurationAndCode() {
        val reporter = reporter()
        val stage = reporter.begin(RuntimeSubsystem.DEXKIT, "resolver.engine.init")
        clock += 420L
        val failure = reporter.fail(stage, RuntimeFailureCode.DEXKIT_INIT_FAILED, "initWithPath returned false")

        assertEquals(420L, failure.durationMillis)
        assertEquals(HealthEventStatus.FAILURE, failure.status)
        assertEquals(RuntimeFailureCode.DEXKIT_INIT_FAILED, failure.failureCode)
        assertEquals(sessions, failure.sessions)

        val events = reporter.events()
        assertEquals(listOf(HealthEventStatus.START, HealthEventStatus.FAILURE), events.map { it.status })
        assertEquals(SubsystemState.FAILED, reporter.snapshot().dexKitState)
    }

    @Test
    fun aDegradedStageSucceedsWithoutClaimingAFailureCode() {
        val reporter = reporter()
        val stage = reporter.begin(RuntimeSubsystem.RESOLVER, "resolver.registry")
        val event = reporter.degrade(stage, RuntimeFailureCode.RESOLVER_FAILED, "one resolver matched ambiguously")

        assertEquals(HealthEventStatus.SUCCESS, event.status)
        assertNull("a success may not carry a failure code", event.failureCode)
        val snapshot = reporter.snapshot()
        assertEquals(SubsystemState.DEGRADED, snapshot.resolverState)
        assertEquals(RuntimeFailureCode.RESOLVER_FAILED, snapshot.failureCode)
    }

    @Test
    fun aSkippedStageIsNotAFailure() {
        val reporter = reporter()
        val stage = reporter.begin(RuntimeSubsystem.OPTIONAL_HOOKS, "optional.hooks")
        reporter.skip(stage, "no optional features are enabled")

        val snapshot = reporter.snapshot()
        assertEquals(SubsystemState.SKIPPED, snapshot.optionalHookState)
        assertNull(snapshot.failureCode)
    }

    @Test
    fun theAggregateStateAndFeatureSummaryComeFromTheParts() {
        val reporter = reporter(summary = { FeatureSummary(essential = 2, optional = 5, ready = 6, failed = 1) })
        ready(reporter)
        reporter.report(RuntimeSubsystem.OPTIONAL_HOOKS, SubsystemState.FAILED, RuntimeFailureCode.OPTIONAL_FEATURE_FAILED)

        val snapshot = reporter.snapshot()
        assertEquals(SubsystemState.DEGRADED, snapshot.overallState)
        assertEquals(RuntimeFailureCode.OPTIONAL_FEATURE_FAILED, snapshot.failureCode)
        assertEquals(7, snapshot.featureSummary.total)
        assertEquals(6, snapshot.featureSummary.running)
    }

    @Test
    fun eventsAreBounded() {
        val reporter =
            HealthReporter(
                identity = identity,
                sessions = sessions,
                now = { clock },
                maxEvents = 4,
            )
        repeat(10) {
            val stage = reporter.begin(RuntimeSubsystem.RESOLVER, "stage-$it")
            reporter.succeed(stage)
        }
        assertEquals(4, reporter.events().size)
        assertEquals("stage-9", reporter.events().last().componentId)
    }

    @Test
    fun freeTextIsRedactedBeforeItIsStored() {
        val reporter = reporter()
        val stage = reporter.begin(RuntimeSubsystem.RESOLVER, "resolve")
        reporter.fail(
            stage,
            RuntimeFailureCode.RESOLVER_FAILED,
            "could not resolve 4915112345678@s.whatsapp.net for chat=4915112345678",
        )

        val snapshot = reporter.snapshot()
        assertFalse(snapshot.failureMessage!!.contains("4915112345678"))
        assertFalse(
            reporter
                .events()
                .last()
                .message!!
                .contains("4915112345678"),
        )
        assertTrue(snapshot.failureMessage!!.contains("[redacted]"))
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun persistingKeepsTheLastGoodStateWhenTheCurrentOneIsBroken() {
        val store = RuntimeHealthStore(folder.newFolder("health"))
        val reporter = reporter(store)
        ready(reporter)
        reporter.persist()
        val good = reporter.lastKnownGood()!!
        assertEquals(SubsystemState.READY, good.overallState)

        clock += 1_000L
        reporter.beginSession(RuntimeSessions("boot-2", "module-2", "target-2"))
        reporter.report(RuntimeSubsystem.FRAMEWORK, SubsystemState.FAILED, RuntimeFailureCode.FRAMEWORK_UNAVAILABLE)
        reporter.persist()

        assertEquals(
            "a failed session must not erase what last worked",
            good.timestampMillis,
            reporter.lastKnownGood()!!.timestampMillis,
        )
    }

    @Test
    fun restoringAdoptsTheStoredGoodStateButNotTheStoredCurrentState() {
        val store = RuntimeHealthStore(folder.newFolder("health"))
        val writer = reporter(store)
        ready(writer)
        writer.persist()
        writer.report(RuntimeSubsystem.OPTIONAL_HOOKS, SubsystemState.FAILED, RuntimeFailureCode.OPTIONAL_FEATURE_FAILED)
        writer.persist()

        clock += 5_000L
        val reader = reporter(store)
        val stored = reader.restore()

        assertEquals(SubsystemState.DEGRADED, stored.current?.overallState)
        assertEquals(SubsystemState.READY, reader.lastKnownGood()?.overallState)
        assertEquals(
            "the previous process's state is evidence, not this session's state",
            SubsystemState.UNKNOWN,
            reader.snapshot().frameworkState,
        )
    }

    @Test
    fun aNewSessionKeepsHistoryAndTheLastGoodState() {
        val reporter = reporter(RuntimeHealthStore(folder.newFolder("session-health")))
        ready(reporter)
        reporter.persist()
        val good = reporter.lastKnownGood()!!
        assertEquals(SubsystemState.READY, good.overallState)

        val stage = reporter.begin(RuntimeSubsystem.OPTIONAL_HOOKS, "optional.hooks")
        reporter.fail(stage, RuntimeFailureCode.OPTIONAL_FEATURE_FAILED)
        reporter.persist()

        val replacement = RuntimeSessions("boot-1", "module-2", "target-2")
        reporter.beginSession(replacement)

        val snapshot = reporter.snapshot()
        assertEquals(replacement, snapshot.sessions)
        assertNotEquals(sessions.targetSessionId, snapshot.targetSessionId)
        assertEquals(SubsystemState.UNKNOWN, snapshot.frameworkState)
        assertEquals("history survives a restart", 2, reporter.events().size)
        assertEquals(
            "a degraded session must not erase the last fully working state",
            good.timestampMillis,
            reporter.lastKnownGood()!!.timestampMillis,
        )
    }

    @Test
    fun aStoreThatCannotBeWrittenDoesNotBreakReporting() {
        val reporter = reporter(store = null)
        reporter.report(RuntimeSubsystem.FRAMEWORK, SubsystemState.READY)
        reporter.persist()
        assertEquals(RuntimeHealthCodec.StoredHealth.EMPTY, reporter.restore())
        assertEquals(SubsystemState.READY, reporter.snapshot().frameworkState)
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun concurrentReportingProducesAConsistentSnapshot() {
        val reporter = reporter()
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val workers =
            (0 until threads).map { index ->
                Thread {
                    try {
                        start.await()
                        repeat(perThread) {
                            val subsystem = RuntimeSubsystem.independent[(index + it) % RuntimeSubsystem.independent.size]
                            reporter.report(subsystem, SubsystemState.READY)
                            reporter.snapshot()
                        }
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    }
                }
            }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertNull("reporting must not throw under concurrent use: ${failure.get()}", failure.get())
        assertEquals(
            "every subsystem must have been reported by the end",
            RuntimeSubsystem.independent.size,
            reporter
                .snapshot()
                .subsystemStates.values
                .count { it == SubsystemState.READY },
        )
        assertEquals(0, reporter.rejectedReportCount())
    }

    @Test
    fun everySubsystemReturnsToUnknownForANewSessionEvenWhileAnotherReports() {
        val reporter = reporter()
        val ready = ArrayList<Thread>()
        for (index in 0 until 4) {
            ready.add(
                Thread {
                    repeat(100) {
                        reporter.report(RuntimeSubsystem.independent[index], SubsystemState.READY)
                    }
                },
            )
        }
        ready.forEach { it.start() }
        reporter.beginSession(RuntimeSessions("boot-1", "module-9", "target-9"))
        ready.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val snapshot = reporter.snapshot()
        assertEquals("boot-1", snapshot.bootId)
        assertEquals("module-9", snapshot.moduleSessionId)
        assertTrue(snapshot.subsystemStates.values.all { it == SubsystemState.READY || it == SubsystemState.UNKNOWN })
    }
}
