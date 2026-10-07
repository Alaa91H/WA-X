package com.wax.module.health

import com.wax.module.diagnostics.ReportRedactor

/**
 * What a caller was told when it reported something.
 *
 * Reporting can fail, and it has to be able to say so. A report that is refused for a
 * reason the caller cannot see is a report the caller will keep making, which is how a
 * model ends up disagreeing with reality in a way nothing can detect.
 */
enum class ReportOutcome {
    /** The report was applied. */
    RECORDED,

    /** The transition is not legal from the subsystem's current state; nothing changed. */
    REJECTED_TRANSITION,

    /**
     * The failure code belongs to another subsystem, so it was reported as
     * [RuntimeFailureCode.UNKNOWN] instead of being attached to the wrong one.
     */
    REJECTED_FOREIGN_CODE,

    /** The runtime's own state is computed and cannot be reported by a component. */
    REJECTED_AGGREGATE,
    ;

    /** Whether the report changed the recorded state. */
    val isRecorded: Boolean get() = this == RECORDED
}

/**
 * The single place the runtime's state is written and read.
 *
 * Everything that wants to say something about the runtime comes through here, which is
 * what makes "the log line said one thing and the screen said another" structurally
 * impossible instead of merely discouraged. Two properties are maintained by construction:
 *
 * * **Free text is redacted on entry.** A component cannot store an identifier in a health
 *   record, because there is no way to pass unredacted text to one.
 * * **A report never throws.** A health system that can break the runtime it describes is
 *   worse than no health system, so every invalid input has an outcome rather than an
 *   exception, and every subsystem may be reported on at any time.
 *
 * The reporter is safe for concurrent use: hook installation and bootstrap stages run on
 * whichever thread the framework called them on.
 */
class HealthReporter(
    private val identity: RuntimeIdentity,
    private var sessions: RuntimeSessions,
    private val store: RuntimeHealthStore? = null,
    private val featureSummary: () -> FeatureSummary = { FeatureSummary() },
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    private val lock = Any()

    private data class Record(
        val state: SubsystemState,
        val code: RuntimeFailureCode?,
        val message: String?,
        val updatedAtMillis: Long,
    )

    private val records = LinkedHashMap<RuntimeSubsystem, Record>()
    private val events = ArrayDeque<HealthEvent>()
    private var lastKnownGood: RuntimeHealthSnapshot? = null
    private var rejected = 0

    /** The identity this reporter describes. */
    fun identity(): RuntimeIdentity = identity

    /** The session identities currently in force. */
    fun sessions(): RuntimeSessions = synchronized(lock) { sessions }

    /**
     * Reports the state of one subsystem.
     *
     * @param code the failure code, when the state is a failure or a degradation. It must
     *   belong to [subsystem], or belong to the runtime as a whole; a code that describes a
     *   different subsystem is refused, because attributing one part's failure to another
     *   is the exact failure mode this model exists to prevent.
     * @param message free text for the diagnostics view; redacted before it is stored
     */
    fun report(
        subsystem: RuntimeSubsystem,
        state: SubsystemState,
        code: RuntimeFailureCode? = null,
        message: String? = null,
    ): ReportOutcome =
        synchronized(lock) {
            if (subsystem.isAggregate) {
                rejected++
                return ReportOutcome.REJECTED_AGGREGATE
            }

            val current = records[subsystem]?.state ?: SubsystemState.UNKNOWN
            if (!current.canTransitionTo(state)) {
                rejected++
                return ReportOutcome.REJECTED_TRANSITION
            }

            val acceptedCode = if (code == null || code.subsystem == subsystem || code.subsystem.isAggregate) code else null
            val foreign = code != null && acceptedCode == null
            if (foreign) rejected++
            records[subsystem] =
                Record(
                    state = state,
                    code = acceptedCode ?: if (foreign) RuntimeFailureCode.UNKNOWN else null,
                    message = message?.let { ReportRedactor.redactAndBound(it) },
                    updatedAtMillis = now(),
                )
            return if (foreign) ReportOutcome.REJECTED_FOREIGN_CODE else ReportOutcome.RECORDED
        }

    /**
     * Records that a stage has begun and returns the event to complete it with.
     *
     * The returned value is the token: completing a stage means handing this event to
     * [succeed], [degrade], [fail] or [skip], so the duration and the session identities of
     * a stage are measured by the reporter rather than assembled by the caller.
     */
    fun begin(
        subsystem: RuntimeSubsystem,
        componentId: String,
    ): HealthEvent =
        synchronized(lock) {
            val event =
                HealthEvent.start(
                    subsystem = subsystem,
                    componentId = ReportRedactor.redactAndBound(componentId),
                    sessions = sessions,
                    atMillis = now(),
                )
            report(subsystem, SubsystemState.STARTING)
            record(event)
            event
        }

    /** Completes a stage as successful and marks the subsystem ready. */
    fun succeed(
        event: HealthEvent,
        message: String? = null,
    ): HealthEvent = complete(event, SubsystemState.READY, null, message, HealthEventStatus.SUCCESS)

    /**
     * Completes a stage as successful but marks the subsystem degraded.
     *
     * The stage did what it could; the subsystem is working through a fallback or with
     * reduced certainty, and [code] is what says which.
     */
    fun degrade(
        event: HealthEvent,
        code: RuntimeFailureCode,
        message: String? = null,
    ): HealthEvent = complete(event, SubsystemState.DEGRADED, code, message, HealthEventStatus.SUCCESS)

    /** Completes a stage as failed, with the code that says how. */
    fun fail(
        event: HealthEvent,
        code: RuntimeFailureCode,
        message: String? = null,
    ): HealthEvent = complete(event, SubsystemState.FAILED, code, message, HealthEventStatus.FAILURE)

    /** Completes a stage as deliberately not attempted. */
    fun skip(
        event: HealthEvent,
        message: String? = null,
    ): HealthEvent = complete(event, SubsystemState.SKIPPED, null, message, HealthEventStatus.SKIPPED)

    private fun complete(
        event: HealthEvent,
        state: SubsystemState,
        code: RuntimeFailureCode?,
        message: String?,
        status: HealthEventStatus,
    ): HealthEvent {
        // Redacted here, before the event exists in memory, and not only when it is written
        // to disk. The first version of this method redacted the subsystem's record and left
        // the event's own message raw, so `events()` could hand a WhatsApp identifier to any
        // caller that displayed it. Redaction by construction has to mean the object, not the
        // file it might end up in.
        val safeMessage = message?.let { ReportRedactor.redactAndBound(it) }
        val completed =
            when (status) {
                HealthEventStatus.SUCCESS -> {
                    HealthEvent.success(event, now(), safeMessage)
                }

                HealthEventStatus.SKIPPED -> {
                    HealthEvent.skipped(event, now(), safeMessage)
                }

                HealthEventStatus.FAILURE -> {
                    HealthEvent.failure(event, code ?: RuntimeFailureCode.UNKNOWN, now(), safeMessage)
                }

                HealthEventStatus.START -> {
                    event
                }
            }
        synchronized(lock) {
            record(completed)
            report(event.subsystem, state, code, safeMessage)
        }
        return completed
    }

    /** The current snapshot. */
    fun snapshot(): RuntimeHealthSnapshot =
        synchronized(lock) {
            val states = RuntimeSubsystem.independent.associateWith { records[it]?.state ?: SubsystemState.UNKNOWN }
            val codes = records.values.mapNotNull { it.code }
            val worst = HealthAggregate.worstCode(codes)
            val worstMessage =
                records.entries
                    .firstOrNull { it.value.code != null && it.value.code == worst }
                    ?.value
                    ?.message
            RuntimeHealthSnapshot.of(
                sessions = sessions,
                timestampMillis = now(),
                packageName = identity.packageName,
                processName = identity.processName,
                pid = identity.pid,
                moduleVersion = identity.moduleVersion,
                targetVersionName = identity.targetVersionName,
                targetVersionCode = identity.targetVersionCode,
                androidSdk = identity.androidSdk,
                states = states,
                featureSummary = featureSummary(),
                overallState = HealthAggregate.overallState(states),
                failureCode = worst,
                failureMessage = worstMessage,
            )
        }

    /** The recorded events, oldest first. */
    fun events(): List<HealthEvent> = synchronized(lock) { events.toList() }

    /** The most recent state in which the runtime was known to be working. */
    fun lastKnownGood(): RuntimeHealthSnapshot? = synchronized(lock) { lastKnownGood }

    /**
     * How many reports have been refused since this reporter was created.
     *
     * Every refusal counts, including the ones whose state was still applied: a foreign
     * failure code is a caller mistake, and a caller mistake that only shows up in a count
     * of transitions would be invisible.
     */
    fun rejectedReportCount(): Int = synchronized(lock) { rejected }

    /**
     * Writes the current document and updates the last-known-good snapshot.
     *
     * The last-known-good value is only replaced by a snapshot in which the runtime was
     * **fully** working — [SubsystemState.READY]. Degraded is deliberately not good enough:
     * a long-running session with one broken optional feature would otherwise overwrite the
     * record of the last time everything worked, and that record is the only thing that can
     * tell a user which state to go back to.
     */
    fun persist() {
        val sink = store ?: return
        val snapshot = snapshot()
        synchronized(lock) {
            if (snapshot.overallState == SubsystemState.READY) lastKnownGood = snapshot
            sink.write(snapshot, lastKnownGood, events.toList())
        }
    }

    /**
     * Reads back what was stored for this target.
     *
     * The current session's own state is not restored: a snapshot from a previous process is
     * evidence about that process, and adopting it as this process's state would be the
     * exact "banner that describes a runtime that is gone" this model replaces. What is
     * restored is the last-known-good snapshot and the recorded history.
     */
    fun restore(): RuntimeHealthCodec.StoredHealth {
        val sink = store ?: return RuntimeHealthCodec.StoredHealth.EMPTY
        val stored = sink.read(identity.targetKey)
        synchronized(lock) {
            lastKnownGood =
                stored.lastKnownGood
                    ?: stored.current?.takeIf { it.overallState == SubsystemState.READY }
        }
        return stored
    }

    /**
     * Starts a new session.
     *
     * A new target process is not a transition from the previous one: nothing has reported
     * in this session yet, so every subsystem returns to [SubsystemState.UNKNOWN]. History
     * and the last-known-good snapshot survive, because each event names its own session and
     * the last good state is exactly what a broken restart needs to be compared against.
     */
    fun beginSession(newSessions: RuntimeSessions) {
        synchronized(lock) {
            sessions = newSessions
            records.clear()
        }
    }

    /** Forgets everything recorded in this process. */
    fun clear() {
        synchronized(lock) {
            records.clear()
            events.clear()
            rejected = 0
        }
    }

    private fun record(event: HealthEvent) {
        if (events.size >= maxEvents) events.removeFirst()
        events.addLast(event)
    }

    companion object {
        /** Events kept in the document. One bootstrap is well under this. */
        const val DEFAULT_MAX_EVENTS: Int = 128
    }
}
