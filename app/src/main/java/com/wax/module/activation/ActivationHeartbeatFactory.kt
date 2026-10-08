package com.wax.module.activation

import com.wax.module.health.RuntimeHealthSnapshot
import com.wax.module.health.SubsystemState

/**
 * Builds the heartbeat a runtime publishes, from the health snapshot it already keeps.
 *
 * This is the join between M01's model and M02's channel, and it is one function on purpose:
 * the snapshot is the only description of the runtime that exists, so deriving the heartbeat
 * from it means the manager can never be shown a state the runtime did not report. A second
 * source of truth for "is the module working" is precisely the defect being removed.
 *
 * The current bootstrap stage is carried because "WA X is executing and starting" and "WA X is
 * executing and stuck" are different situations, and the stage name is what tells them apart
 * without a second message channel.
 */
object ActivationHeartbeatFactory {
    /** The stage used before any stage has been named, so a heartbeat is never stage-less. */
    const val STAGE_ENTRY: String = "bootstrap.entry"

    /**
     * The heartbeat for [snapshot] at stage [stage].
     *
     * Returns null when the snapshot describes nothing a heartbeat can prove. The aggregate
     * being [SubsystemState.UNKNOWN] is exactly that case: no subsystem has reported, so
     * publishing it would put a record on the wire whose only content is "something happened".
     */
    fun from(
        snapshot: RuntimeHealthSnapshot,
        stage: String = STAGE_ENTRY,
    ): TargetHeartbeat? {
        val state = normalise(snapshot.overallState) ?: return null
        return runCatching {
            TargetHeartbeat(
                packageName = snapshot.packageName,
                processName = snapshot.processName,
                pid = snapshot.pid,
                bootId = snapshot.bootId,
                moduleSessionId = snapshot.moduleSessionId,
                targetSessionId = snapshot.targetSessionId,
                stage = stage,
                state = state,
                failureCode = snapshot.failureCode?.takeIf { state == SubsystemState.FAILED || state == SubsystemState.DEGRADED },
                timestampMillis = snapshot.timestampMillis,
                moduleVersion = snapshot.moduleVersion,
                targetVersionName = snapshot.targetVersionName,
            )
        }.getOrNull()
    }

    /**
     * The aggregate states a heartbeat may carry.
     *
     * [SubsystemState.UNKNOWN] is rejected: it is the model's "nothing reported", and a
     * heartbeat whose whole content is "nothing reported" is not evidence. Everything else is
     * a real claim about a running process and is carried through unchanged - including
     * [SubsystemState.UNAVAILABLE] and [SubsystemState.SKIPPED], which are claims too.
     */
    private fun normalise(state: SubsystemState): SubsystemState? = if (state == SubsystemState.UNKNOWN) null else state
}
