package com.wax.module.health

/**
 * The one place a single verdict is computed, and the only place one may be computed.
 *
 * A subsystem never reports on the runtime as a whole, and this is why: a verdict assembled
 * from the parts is reproducible and reviewable, while a verdict each component writes
 * about itself is how the module ends up claiming it is fine because the component that
 * failed was not asked.
 *
 * The rules are ordered, and the ordering is the specification:
 *
 * 1. Nothing reported: [SubsystemState.UNKNOWN]. Something is always better than a guess.
 * 2. A core subsystem failed or is absent: [SubsystemState.FAILED]. The core set is the
 *    parts the runtime exists to provide — the framework, the module, the scope, injection,
 *    the shared components and the hooks that are not optional.
 * 3. Anything else failed or degraded: [SubsystemState.DEGRADED]. The runtime works; a
 *    capability does not.
 * 4. A stage is running: [SubsystemState.STARTING].
 * 5. Some subsystems have reported and others have not: [SubsystemState.DEGRADED]. Not
 *    knowing is not the same as being fine, and calling an incomplete bootstrap ready is
 *    the specific claim this program exists to remove.
 * 6. Everything definitive and working: [SubsystemState.READY]. Note that
 *    [SubsystemState.UNAVAILABLE] and [SubsystemState.SKIPPED] on a non-core subsystem are
 *    compatible with this: a subsystem that is not part of this environment, or that the
 *    user turned off, is not a fault.
 */
object HealthAggregate {
    /** The subsystems whose failure means the runtime itself is not working. */
    val CORE_SUBSYSTEMS: List<RuntimeSubsystem> =
        listOf(
            RuntimeSubsystem.FRAMEWORK,
            RuntimeSubsystem.MODULE,
            RuntimeSubsystem.SCOPE,
            RuntimeSubsystem.INJECTION,
            RuntimeSubsystem.CORE_COMPONENTS,
            RuntimeSubsystem.ESSENTIAL_HOOKS,
        )

    /** Computes the state of the runtime from the states of its parts. */
    fun overallState(states: Map<RuntimeSubsystem, SubsystemState>): SubsystemState {
        val relevant = RuntimeSubsystem.independent.associateWith { states[it] ?: SubsystemState.UNKNOWN }
        if (relevant.values.all { it == SubsystemState.UNKNOWN }) return SubsystemState.UNKNOWN

        val core = CORE_SUBSYSTEMS.map { relevant.getValue(it) }
        if (core.any { it == SubsystemState.FAILED || it == SubsystemState.UNAVAILABLE }) {
            return SubsystemState.FAILED
        }
        if (relevant.values.any { it == SubsystemState.FAILED || it == SubsystemState.DEGRADED }) {
            return SubsystemState.DEGRADED
        }
        if (relevant.values.any { it == SubsystemState.STARTING }) return SubsystemState.STARTING
        if (relevant.values.any { it == SubsystemState.UNKNOWN }) return SubsystemState.DEGRADED
        return SubsystemState.READY
    }

    /**
     * The code reported for the runtime as a whole.
     *
     * Severity decides, and declaration order breaks ties, so the answer does not depend on
     * which subsystem happened to be iterated first.
     */
    fun worstCode(codes: Collection<RuntimeFailureCode>): RuntimeFailureCode? = RuntimeFailureCode.mostSevere(codes)
}
