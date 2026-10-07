package com.wax.module.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model primitives, tested where they decide something.
 *
 * These are the three claims the rest of the runtime depends on: a subsystem's state only
 * moves in ways that cannot hide a failure, freshness is decided in one place, and a
 * failure code can only ever describe the subsystem it belongs to.
 */
class RuntimeHealthModelTest {
    // ---------------------------------------------------------------- transitions

    @Test
    fun aSubsystemNeverReturnsToUnknown() {
        for (state in SubsystemState.entries) {
            if (state == SubsystemState.UNKNOWN) continue
            assertFalse(
                "$state -> UNKNOWN would let a late stage erase a recorded failure",
                state.canTransitionTo(SubsystemState.UNKNOWN),
            )
        }
    }

    @Test
    fun reReportingTheSameStateIsAllowed() {
        for (state in SubsystemState.entries) {
            assertTrue("$state -> $state must stay idempotent", state.canTransitionTo(state))
        }
    }

    @Test
    fun aStageMustBeAttemptedBeforeItCanHaveAnOutcome() {
        for (untried in listOf(SubsystemState.UNAVAILABLE, SubsystemState.SKIPPED)) {
            assertTrue(untried.canTransitionTo(SubsystemState.STARTING))
            assertTrue(untried.canTransitionTo(SubsystemState.READY))
            assertFalse(
                "$untried -> FAILED claims an outcome for something never attempted",
                untried.canTransitionTo(SubsystemState.FAILED),
            )
            assertFalse(
                "$untried -> DEGRADED claims an outcome for something never attempted",
                untried.canTransitionTo(SubsystemState.DEGRADED),
            )
        }
    }

    @Test
    fun failuresCanBeRetriedAndDegradedSubsystemsCanRecover() {
        assertTrue(SubsystemState.FAILED.canTransitionTo(SubsystemState.STARTING))
        assertTrue(SubsystemState.FAILED.canTransitionTo(SubsystemState.READY))
        assertTrue(SubsystemState.DEGRADED.canTransitionTo(SubsystemState.READY))
        assertTrue(SubsystemState.READY.canTransitionTo(SubsystemState.DEGRADED))
        assertTrue(SubsystemState.READY.canTransitionTo(SubsystemState.FAILED))
    }

    // ---------------------------------------------------------------- freshness

    @Test
    fun freshnessBoundariesAreInclusiveOnTheFresherSide() {
        assertEquals(HealthFreshness.FRESH, HealthFreshness.of(0L))
        assertEquals(HealthFreshness.FRESH, HealthFreshness.of(HealthFreshness.FRESH_UNTIL_MILLIS - 1))
        assertEquals(HealthFreshness.STALE, HealthFreshness.of(HealthFreshness.FRESH_UNTIL_MILLIS))
        assertEquals(HealthFreshness.STALE, HealthFreshness.of(HealthFreshness.EXPIRED_AFTER_MILLIS))
        assertEquals(HealthFreshness.EXPIRED, HealthFreshness.of(HealthFreshness.EXPIRED_AFTER_MILLIS + 1))
    }

    @Test
    fun aFutureTimestampIsFreshRatherThanImpossible() {
        assertEquals(HealthFreshness.FRESH, HealthFreshness.of(-1L))
        assertEquals(HealthFreshness.FRESH, HealthFreshness.of(-86_400_000L))
    }

    @Test
    fun aMissingTimestampIsAbsentRatherThanAncient() {
        assertEquals(HealthFreshness.ABSENT, HealthFreshness.at(0L, 1_000L))
        assertEquals(HealthFreshness.ABSENT, HealthFreshness.at(-5L, 1_000L))
        assertEquals(HealthFreshness.FRESH, HealthFreshness.at(1_000L, 1_500L))
    }

    // ---------------------------------------------------------------- failure codes

    @Test
    fun everyFailureCodeBelongsToExactlyOneSubsystem() {
        for (code in RuntimeFailureCode.entries) {
            assertTrue(
                "${code.name} belongs to ${code.subsystem}, which must not be the runtime's own state",
                code.subsystem == RuntimeSubsystem.OVERALL_RUNTIME || !code.subsystem.isAggregate,
            )
            assertEquals(
                "${code.name} is listed under the wrong subsystem",
                code,
                RuntimeFailureCode.forSubsystem(code.subsystem).firstOrNull { it == code },
            )
        }
    }

    @Test
    fun everyIndependentSubsystemHasAtLeastOneCodeOfItsOwn() {
        // A subsystem with no code of its own could only report failures as UNKNOWN, which
        // is the state the taxonomy exists to avoid.
        val withoutCode = RuntimeSubsystem.independent.filter { RuntimeFailureCode.forSubsystem(it).isEmpty() }
        assertTrue("no subsystem may be code-less: $withoutCode", withoutCode.isEmpty())
        assertTrue(
            "the runtime-wide codes must exist, or a stage that fails as a whole cannot say so",
            RuntimeFailureCode.forSubsystem(RuntimeSubsystem.OVERALL_RUNTIME).isNotEmpty(),
        )
    }

    @Test
    fun theMostSevereCodeWinsAndTiesAreStable() {
        assertEquals(
            RuntimeFailureCode.CORE_COMPONENT_FAILED,
            RuntimeFailureCode.mostSevere(
                listOf(
                    RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
                    RuntimeFailureCode.CORE_COMPONENT_FAILED,
                    RuntimeFailureCode.HEARTBEAT_STALE,
                ),
            ),
        )
        assertEquals(
            RuntimeFailureCode.FRAMEWORK_UNAVAILABLE,
            RuntimeFailureCode.mostSevere(listOf(RuntimeFailureCode.FRAMEWORK_UNAVAILABLE)),
        )
        assertEquals(null, RuntimeFailureCode.mostSevere(emptyList()))
        assertEquals(
            "an equal-severity tie is broken by declaration order, so the answer is stable",
            RuntimeFailureCode.MODULE_DISABLED,
            RuntimeFailureCode.mostSevere(
                listOf(RuntimeFailureCode.SCOPE_MISSING, RuntimeFailureCode.MODULE_DISABLED),
            ),
        )
    }

    @Test
    fun severityOrdersCriticalBelowMajorBelowMinor() {
        assertTrue(FailureSeverity.CRITICAL.rank < FailureSeverity.MAJOR.rank)
        assertTrue(FailureSeverity.MAJOR.rank < FailureSeverity.MINOR.rank)
    }

    // ---------------------------------------------------------------- sessions

    @Test
    fun aCreatedSessionHasThreeDistinctIdentities() {
        val counter = intArrayOf(0)
        val sessions = RuntimeSessions.create("boot-1") { "id-${counter[0]++}" }
        assertEquals("boot-1", sessions.bootId)
        assertNotEquals(sessions.moduleSessionId, sessions.targetSessionId)
        assertTrue(sessions.isComplete)
    }

    @Test
    fun theUnknownSessionIsNeverComplete() {
        assertFalse(RuntimeSessions.unknown().isComplete)
        assertFalse(RuntimeSessions.create("") { RuntimeSessions.NO_ID }.isComplete)
    }

    @Test
    fun bootIdentityIsStableForTheSameBootAndChangesWithIt() {
        val first = BootIdentity.of(elapsedRealtimeMillis = 5_000L, nowMillis = 1_000_000L)
        val sameBoot = BootIdentity.of(elapsedRealtimeMillis = 9_000L, nowMillis = 1_004_000L)
        val nextBoot = BootIdentity.of(elapsedRealtimeMillis = 100L, nowMillis = 2_000_000L)
        assertEquals(first, sameBoot)
        assertNotEquals(first, nextBoot)
        assertFalse(first.contains("com.whatsapp"))
    }
}
