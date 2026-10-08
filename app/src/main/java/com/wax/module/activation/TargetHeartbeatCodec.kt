package com.wax.module.activation

import com.wax.module.health.HealthJson
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState

/**
 * The wire format for a [TargetHeartbeat].
 *
 * The heartbeat travels in a broadcast between two processes of the same app, so the format
 * has to survive three things going wrong: a version of the module on one side that is not the
 * version on the other, a truncated payload, and a payload that is not a payload at all. All
 * three degrade to "no heartbeat" rather than to an exception, because a reader that throws
 * inside a `BroadcastReceiver` is a crash in the Manager caused by the runtime.
 *
 * The JSON reader is [HealthJson] rather than a second one. It is module-internal, it is
 * hand-rolled for exactly this reason (`org.json` is not on a JVM unit test's classpath, and
 * this format has to be verifiable in CI), and a second parser for a second document would be
 * a second thing to keep correct.
 *
 * Reading degrades in a fixed order: an unknown enum name falls back to the model's own
 * "nothing reported" value rather than failing the whole record, because a heartbeat from a
 * newer module is still evidence that *something* reported.
 */
object TargetHeartbeatCodec {
    /** Identifies the format, so a future revision can be told apart from this one. */
    const val SCHEMA: String = "wax.m02.activation-heartbeat/1"

    private const val KEY_SCHEMA = "schema"
    private const val KEY_PACKAGE = "package"
    private const val KEY_PROCESS = "process"
    private const val KEY_PID = "pid"
    private const val KEY_BOOT = "boot"
    private const val KEY_MODULE_SESSION = "moduleSession"
    private const val KEY_TARGET_SESSION = "targetSession"
    private const val KEY_STAGE = "stage"
    private const val KEY_STATE = "state"
    private const val KEY_CODE = "code"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_MODULE_VERSION = "moduleVersion"
    private const val KEY_TARGET_VERSION = "targetVersion"

    /**
     * Upper bound on every string in the record.
     *
     * The payload arrives from another process, so its size is not this code's decision to
     * make. A stage id longer than this is not a stage id.
     */
    private const val MAX_STRING = 128

    /** Encodes [heartbeat]. */
    fun encode(heartbeat: TargetHeartbeat): String =
        HealthJson.write(
            linkedMapOf(
                KEY_SCHEMA to SCHEMA,
                KEY_PACKAGE to heartbeat.packageName,
                KEY_PROCESS to heartbeat.processName,
                KEY_PID to heartbeat.pid,
                KEY_BOOT to heartbeat.bootId,
                KEY_MODULE_SESSION to heartbeat.moduleSessionId,
                KEY_TARGET_SESSION to heartbeat.targetSessionId,
                KEY_STAGE to heartbeat.stage,
                KEY_STATE to heartbeat.state.name,
                KEY_CODE to heartbeat.failureCode?.name,
                KEY_TIMESTAMP to heartbeat.timestampMillis,
                KEY_MODULE_VERSION to heartbeat.moduleVersion,
                KEY_TARGET_VERSION to heartbeat.targetVersionName,
            ),
        )

    /**
     * Decodes [text], or returns null when it is not a heartbeat this version understands.
     *
     * Null means "no evidence", which is a different answer from "evidence of nothing", and
     * the caller has to keep the two apart: a null here must not become a failure code.
     */
    fun decode(text: String?): TargetHeartbeat? {
        val document = HealthJson.read(text) as? Map<*, *> ?: return null
        if (document[KEY_SCHEMA] != SCHEMA) return null

        val packageName = document.string(KEY_PACKAGE) ?: return null
        val processName = document.string(KEY_PROCESS) ?: return null
        val pid = document.long(KEY_PID)?.toInt() ?: return null
        val bootId = document.string(KEY_BOOT) ?: return null
        val moduleSession = document.string(KEY_MODULE_SESSION) ?: return null
        val targetSession = document.string(KEY_TARGET_SESSION) ?: return null
        val stage = document.string(KEY_STAGE) ?: return null
        val state = SubsystemState.entries.firstOrNull { it.name == document[KEY_STATE] } ?: return null
        val timestamp = document.long(KEY_TIMESTAMP) ?: return null
        val moduleVersion = document.string(KEY_MODULE_VERSION) ?: return null
        val code = (document[KEY_CODE] as? String)?.let { name -> RuntimeFailureCode.entries.firstOrNull { it.name == name } }

        return runCatching {
            TargetHeartbeat(
                packageName = packageName,
                processName = processName,
                pid = pid,
                bootId = bootId,
                moduleSessionId = moduleSession,
                targetSessionId = targetSession,
                stage = stage,
                state = state,
                // A code this version does not know is dropped rather than guessed at: naming
                // a failure we cannot describe is how "unknown" becomes a wrong answer.
                failureCode = code,
                timestampMillis = timestamp,
                moduleVersion = moduleVersion,
                targetVersionName = document.string(KEY_TARGET_VERSION),
            )
        }.getOrNull()
    }

    private fun Map<*, *>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_STRING }

    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Long)
}
