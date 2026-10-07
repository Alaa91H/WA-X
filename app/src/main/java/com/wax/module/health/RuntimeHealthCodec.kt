package com.wax.module.health

import com.wax.module.diagnostics.ReportRedactor

/**
 * Reads and writes the stored health document.
 *
 * The stored shape is one file per target, because WhatsApp and WhatsApp Business are
 * different applications and a single shared file would let one target's state be read as
 * the other's. Inside, the document holds the current snapshot, the last snapshot that was
 * known-good, and a bounded history of events.
 *
 * Decoding is deliberately forgiving. The file is read at startup and after a crash, which
 * is exactly when it is most likely to be truncated or half-written, so a value that
 * cannot be understood becomes a default rather than an exception, an unknown enum name
 * becomes [SubsystemState.UNKNOWN] or [RuntimeFailureCode.UNKNOWN] rather than discarding
 * the record, and a document that cannot be parsed at all reads as "nothing recorded".
 * Silently losing one field is recoverable; refusing to start is not.
 *
 * Free text is redacted on the way out *and* on the way in. Outbound, so a message can
 * never be stored carrying an identifier; inbound, so a file that was edited or written by
 * an older, less careful revision cannot put one on screen.
 */
object RuntimeHealthCodec {
    /** Document schema identifier. Bumped when the stored shape changes incompatibly. */
    const val SCHEMA: String = "wax.m01.runtime-health/1"

    /** What one stored file contains. */
    data class StoredHealth(
        val current: RuntimeHealthSnapshot?,
        val lastKnownGood: RuntimeHealthSnapshot?,
        val history: List<HealthEvent>,
    ) {
        companion object {
            /** A document with nothing in it. */
            val EMPTY: StoredHealth = StoredHealth(current = null, lastKnownGood = null, history = emptyList())
        }
    }

    /** Encodes one target's document. */
    fun encode(
        current: RuntimeHealthSnapshot,
        lastKnownGood: RuntimeHealthSnapshot?,
        history: List<HealthEvent>,
    ): String =
        HealthJson.write(
            linkedMapOf(
                "schema" to SCHEMA,
                "current" to snapshotToJson(current),
                "lastKnownGood" to lastKnownGood?.let { snapshotToJson(it) },
                "history" to history.map { eventToJson(it) },
            ),
        )

    /** Decodes a document, salvaging whatever is intact. */
    fun decode(text: String?): StoredHealth {
        val root = HealthJson.read(text) as? Map<*, *> ?: return StoredHealth.EMPTY
        val current = snapshotFromJson(root["current"])
        val lastKnownGood = snapshotFromJson(root["lastKnownGood"])
        val history = (root["history"] as? List<*>).orEmpty().mapNotNull { eventFromJson(it, current) }
        return StoredHealth(current = current, lastKnownGood = lastKnownGood, history = history)
    }

    // ------------------------------------------------------------------ writing

    private fun snapshotToJson(snapshot: RuntimeHealthSnapshot): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "bootId" to snapshot.bootId,
            "moduleSessionId" to snapshot.moduleSessionId,
            "targetSessionId" to snapshot.targetSessionId,
            "timestamp" to snapshot.timestampMillis,
            "package" to snapshot.packageName,
            "process" to snapshot.processName,
            "pid" to snapshot.pid,
            "moduleVersion" to snapshot.moduleVersion,
            "targetVersionName" to snapshot.targetVersionName,
            "targetVersionCode" to snapshot.targetVersionCode,
            "androidSdk" to snapshot.androidSdk,
            "overall" to snapshot.overallState.name,
            "failureCode" to snapshot.failureCode?.name,
            "failureMessage" to snapshot.failureMessage,
            "subsystems" to
                RuntimeSubsystem.independent.associate { it.name to snapshot.stateOf(it).name },
            "features" to
                linkedMapOf<String, Any?>(
                    "essential" to snapshot.featureSummary.essential,
                    "optional" to snapshot.featureSummary.optional,
                    "ready" to snapshot.featureSummary.ready,
                    "degraded" to snapshot.featureSummary.degraded,
                    "failed" to snapshot.featureSummary.failed,
                    "notAttempted" to snapshot.featureSummary.notAttempted,
                ),
        )

    private fun eventToJson(event: HealthEvent): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "subsystem" to event.subsystem.name,
            "component" to event.componentId,
            "status" to event.status.name,
            "startedAt" to event.startedAtMillis,
            "duration" to event.durationMillis,
            "bootId" to event.sessions.bootId,
            "moduleSessionId" to event.sessions.moduleSessionId,
            "targetSessionId" to event.sessions.targetSessionId,
            "failureCode" to event.failureCode?.name,
            "message" to event.message,
        )

    // ------------------------------------------------------------------ reading

    private fun snapshotFromJson(value: Any?): RuntimeHealthSnapshot? {
        val fields = value as? Map<*, *> ?: return null
        val packageName = fields.string("package") ?: return null
        val sessions =
            RuntimeSessions(
                bootId = fields.string("bootId") ?: RuntimeSessions.NO_ID,
                moduleSessionId = fields.string("moduleSessionId") ?: RuntimeSessions.NO_ID,
                targetSessionId = fields.string("targetSessionId") ?: RuntimeSessions.NO_ID,
            )
        val states = LinkedHashMap<RuntimeSubsystem, SubsystemState>()
        val recorded = fields["subsystems"] as? Map<*, *>
        for (subsystem in RuntimeSubsystem.entries) {
            states[subsystem] = recorded.state(subsystem)
        }
        val features = fields["features"] as? Map<*, *>
        return RuntimeHealthSnapshot.of(
            sessions = sessions,
            timestampMillis = fields.long("timestamp") ?: 0L,
            packageName = packageName,
            processName = fields.string("process").orEmpty(),
            pid = (fields.long("pid") ?: 0L).toInt(),
            moduleVersion = fields.string("moduleVersion").orEmpty(),
            targetVersionName = fields.string("targetVersionName"),
            targetVersionCode = fields.long("targetVersionCode"),
            androidSdk = (fields.long("androidSdk") ?: 0L).toInt(),
            states = states,
            featureSummary =
                FeatureSummary(
                    essential = (features.long("essential") ?: 0L).toInt(),
                    optional = (features.long("optional") ?: 0L).toInt(),
                    ready = (features.long("ready") ?: 0L).toInt(),
                    degraded = (features.long("degraded") ?: 0L).toInt(),
                    failed = (features.long("failed") ?: 0L).toInt(),
                    notAttempted = (features.long("notAttempted") ?: 0L).toInt(),
                ),
            overallState = fields.state("overall"),
            failureCode = fields.failureCode("failureCode"),
            failureMessage = fields.string("failureMessage")?.let { ReportRedactor.redactAndBound(it) },
        )
    }

    private fun eventFromJson(
        value: Any?,
        current: RuntimeHealthSnapshot?,
    ): HealthEvent? {
        val fields = value as? Map<*, *> ?: return null
        val subsystem = fields.enumName("subsystem")?.let { name -> RuntimeSubsystem.entries.firstOrNull { it.name == name } }
        val status = fields.enumName("status")?.let { name -> HealthEventStatus.entries.firstOrNull { it.name == name } }
        val component = fields.string("component") ?: return null
        if (subsystem == null || status == null || subsystem.isAggregate) return null

        val sessions =
            RuntimeSessions(
                bootId = fields.string("bootId") ?: current?.bootId ?: RuntimeSessions.NO_ID,
                moduleSessionId = fields.string("moduleSessionId") ?: current?.moduleSessionId ?: RuntimeSessions.NO_ID,
                targetSessionId = fields.string("targetSessionId") ?: current?.targetSessionId ?: RuntimeSessions.NO_ID,
            )
        val code = fields.failureCode("failureCode")
        // An event whose status and code disagree is repaired rather than dropped: a
        // stored FAILURE with no code becomes UNKNOWN so it is still visible, and a code
        // on a success is discarded because a success has nothing to report.
        val repairedStatus = status
        val repairedCode =
            when {
                repairedStatus == HealthEventStatus.FAILURE -> code ?: RuntimeFailureCode.UNKNOWN
                else -> null
            }
        return HealthEvent(
            subsystem = subsystem,
            componentId = ReportRedactor.redactAndBound(component),
            status = repairedStatus,
            startedAtMillis = fields.long("startedAt") ?: 0L,
            durationMillis = (fields.long("duration") ?: 0L).coerceAtLeast(0L),
            sessions = sessions,
            failureCode = repairedCode,
            message = fields.string("message")?.let { ReportRedactor.redactAndBound(it) },
        )
    }

    private fun Any?.string(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

    private fun Any?.long(key: String): Long? = (this as? Map<*, *>)?.get(key) as? Long

    private fun Any?.enumName(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

    private fun Any?.state(subsystem: RuntimeSubsystem): SubsystemState {
        val name = enumName(subsystem.name) ?: return SubsystemState.UNKNOWN
        return SubsystemState.entries.firstOrNull { it.name == name } ?: SubsystemState.UNKNOWN
    }

    private fun Any?.state(key: String): SubsystemState {
        val name = enumName(key) ?: return SubsystemState.UNKNOWN
        return SubsystemState.entries.firstOrNull { it.name == name } ?: SubsystemState.UNKNOWN
    }

    private fun Any?.failureCode(key: String): RuntimeFailureCode? {
        val name = enumName(key) ?: return null
        return RuntimeFailureCode.entries.firstOrNull { it.name == name } ?: RuntimeFailureCode.UNKNOWN
    }
}
