package com.wax.module.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aggregation rules, one test per rule, because the order of the rules is the
 * specification: a change that reorders them changes what the runtime claims about itself.
 */
class HealthAggregateTest {
    private fun states(vararg pairs: Pair<RuntimeSubsystem, SubsystemState>): Map<RuntimeSubsystem, SubsystemState> = pairs.toMap()

    private fun all(state: SubsystemState): Map<RuntimeSubsystem, SubsystemState> = RuntimeSubsystem.independent.associateWith { state }

    @Test
    fun nothingReportedIsUnknownAndNotHealthy() {
        assertEquals(SubsystemState.UNKNOWN, HealthAggregate.overallState(all(SubsystemState.UNKNOWN)))
        assertEquals(SubsystemState.UNKNOWN, HealthAggregate.overallState(emptyMap()))
    }

    @Test
    fun aFailedCoreSubsystemMakesTheRuntimeFailed() {
        for (core in HealthAggregate.CORE_SUBSYSTEMS) {
            val map = all(SubsystemState.READY).toMutableMap()
            map[core] = SubsystemState.FAILED
            assertEquals(
                "$core failing must not leave the runtime reporting ready",
                SubsystemState.FAILED,
                HealthAggregate.overallState(map),
            )
        }
    }

    @Test
    fun anAbsentCoreSubsystemIsAlsoFailure() {
        for (core in HealthAggregate.CORE_SUBSYSTEMS) {
            val map = all(SubsystemState.READY).toMutableMap()
            map[core] = SubsystemState.UNAVAILABLE
            assertEquals(
                "$core being absent means the runtime is not there",
                SubsystemState.FAILED,
                HealthAggregate.overallState(map),
            )
        }
    }

    @Test
    fun aFailedOptionalSubsystemDegradesRatherThanFailing() {
        val map = all(SubsystemState.READY).toMutableMap()
        map[RuntimeSubsystem.OPTIONAL_HOOKS] = SubsystemState.FAILED
        map[RuntimeSubsystem.RESOLVER] = SubsystemState.DEGRADED
        assertEquals(SubsystemState.DEGRADED, HealthAggregate.overallState(map))
    }

    @Test
    fun anAbsentNonCoreSubsystemIsNotAFault() {
        val map = all(SubsystemState.READY).toMutableMap()
        map[RuntimeSubsystem.TARGET_PROCESS] = SubsystemState.UNAVAILABLE
        map[RuntimeSubsystem.OPTIONAL_HOOKS] = SubsystemState.SKIPPED
        assertEquals(
            "a subsystem that is not part of this environment is not a failure",
            SubsystemState.READY,
            HealthAggregate.overallState(map),
        )
    }

    @Test
    fun anIncompleteBootstrapIsNotReady() {
        val map =
            states(
                RuntimeSubsystem.FRAMEWORK to SubsystemState.READY,
                RuntimeSubsystem.MODULE to SubsystemState.READY,
            )
        assertEquals(
            "only two subsystems reporting must not produce a ready verdict",
            SubsystemState.DEGRADED,
            HealthAggregate.overallState(map),
        )
    }

    @Test
    fun aRunningStageOutranksIncompleteKnowledge() {
        val map = all(SubsystemState.READY).toMutableMap()
        map[RuntimeSubsystem.INJECTION] = SubsystemState.STARTING
        map[RuntimeSubsystem.DEXKIT] = SubsystemState.UNKNOWN
        assertEquals(SubsystemState.STARTING, HealthAggregate.overallState(map))
    }

    @Test
    fun everythingDefinitiveAndWorkingIsReady() {
        assertEquals(SubsystemState.READY, HealthAggregate.overallState(all(SubsystemState.READY)))
    }

    @Test
    fun theWorstCodeIsTheOneReportedForTheRuntime() {
        assertEquals(
            RuntimeFailureCode.CORE_COMPONENT_FAILED,
            HealthAggregate.worstCode(listOf(RuntimeFailureCode.HEARTBEAT_STALE, RuntimeFailureCode.CORE_COMPONENT_FAILED)),
        )
        assertEquals(null, HealthAggregate.worstCode(emptyList()))
    }

    @Test
    fun theCoreSetNeverContainsOptionalWork() {
        assertTrue("the aggregate state is computed, never core", HealthAggregate.CORE_SUBSYSTEMS.none { it.isAggregate })
        assertTrue(
            "optional hooks must never be core, or a feature failure would read as a broken module",
            !HealthAggregate.CORE_SUBSYSTEMS.contains(RuntimeSubsystem.OPTIONAL_HOOKS),
        )
        assertTrue(
            "the framework is core: without it the module is not loaded at all",
            HealthAggregate.CORE_SUBSYSTEMS.contains(RuntimeSubsystem.FRAMEWORK),
        )
    }
}
