package com.wax.module.health

/**
 * One stage of one subsystem, observed.
 *
 * Events are the history that explains a state: the snapshot says DexKit is
 * [SubsystemState.FAILED], and the event stream says the failure happened during
 * initialisation after 420ms with [RuntimeFailureCode.DEXKIT_INIT_FAILED]. Without the
 * second half, "DexKit failed" is a claim nobody can act on.
 *
 * The status and the failure code are kept consistent by construction: [FAILURE] always
 * carries a code, and nothing else may. A success that also reports a failure code is a
 * contradiction, and one that would let a failure hide inside a successful stage.
 */
data class HealthEvent(
    val subsystem: RuntimeSubsystem,
    val componentId: String,
    val status: HealthEventStatus,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val sessions: RuntimeSessions,
    val failureCode: RuntimeFailureCode? = null,
    val message: String? = null,
) {
    init {
        require(durationMillis >= 0L) { "a stage cannot take a negative time: $durationMillis" }
        require(status == HealthEventStatus.FAILURE || failureCode == null) {
            "only a failure may carry a failure code, and $status carried $failureCode"
        }
        require(status != HealthEventStatus.FAILURE || failureCode != null) {
            "a failure must say what failed; $componentId reported FAILURE with no code"
        }
    }

    /** Whether this event changes what a user should be told. */
    val isNotable: Boolean get() = status == HealthEventStatus.FAILURE

    companion object {
        /** An event reporting that a stage has begun. */
        fun start(
            subsystem: RuntimeSubsystem,
            componentId: String,
            sessions: RuntimeSessions,
            atMillis: Long,
        ): HealthEvent =
            HealthEvent(
                subsystem = subsystem,
                componentId = componentId,
                status = HealthEventStatus.START,
                startedAtMillis = atMillis,
                durationMillis = 0L,
                sessions = sessions,
            )

        /** An event reporting that a stage completed successfully. */
        fun success(
            event: HealthEvent,
            atMillis: Long,
            message: String? = null,
        ): HealthEvent = event.completed(HealthEventStatus.SUCCESS, atMillis, code = null, message = message)

        /** An event reporting that a stage failed, with the code that says how. */
        fun failure(
            event: HealthEvent,
            code: RuntimeFailureCode,
            atMillis: Long,
            message: String? = null,
        ): HealthEvent = event.completed(HealthEventStatus.FAILURE, atMillis, code, message)

        /** An event reporting that a stage was deliberately not attempted. */
        fun skipped(
            event: HealthEvent,
            atMillis: Long,
            message: String? = null,
        ): HealthEvent = event.completed(HealthEventStatus.SKIPPED, atMillis, code = null, message)

        private fun HealthEvent.completed(
            status: HealthEventStatus,
            atMillis: Long,
            code: RuntimeFailureCode?,
            message: String?,
        ): HealthEvent =
            HealthEvent(
                subsystem = subsystem,
                componentId = componentId,
                status = status,
                startedAtMillis = startedAtMillis,
                durationMillis = (atMillis - startedAtMillis).coerceAtLeast(0L),
                sessions = sessions,
                failureCode = code,
                message = message,
            )
    }
}

/** Which point of a stage's life an event describes. */
enum class HealthEventStatus {
    START,
    SUCCESS,
    FAILURE,
    SKIPPED,
}
