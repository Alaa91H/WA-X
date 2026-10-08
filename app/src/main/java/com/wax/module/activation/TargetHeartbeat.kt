package com.wax.module.activation

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState

/**
 * What the injected runtime says about itself, once, from inside the target's process.
 *
 * A heartbeat is the answer to "is WA X actually running in this process right now", and it
 * can only be produced by code that is executing there - which is what makes it evidence
 * rather than an opinion. Everything the previous banner asserted came from the module's own
 * process; this comes from the target's, keyed by package *and* process, so a WhatsApp
 * heartbeat can never be read as a WhatsApp Business one.
 *
 * Three properties are enforced by construction rather than by discipline:
 *
 * * **No free text.** There is no message field. The reason lives in [failureCode], which is
 *   an enum, so a heartbeat cannot carry a contact name, a JID or a log line to the manager.
 * * **Process-aware.** [processName] is part of the identity, not a label, and [targetKey] is
 *   what storage is keyed by.
 * * **Self-dating.** [timestampMillis] is the time the heartbeat was produced, so the age
 *   that decides [HealthFreshness] cannot be recomputed differently by each reader.
 *
 * [pid] and the session identities are carried because "a heartbeat from a process that has
 * since been replaced" is a distinction the manager cannot make on its own: a fresh report
 * from a dead pid is a restart, and a restart is a different thing to tell a user than a
 * failure.
 */
data class TargetHeartbeat(
    /** The target package, e.g. `com.whatsapp`. */
    val packageName: String,
    /** The target process, e.g. `com.whatsapp` or `com.whatsapp:business`. */
    val processName: String,
    /** The process id this heartbeat was produced in. */
    val pid: Int,
    /** Boot identity, so a heartbeat cannot survive a reboot and still read as current. */
    val bootId: String,
    /** The module session that produced it. */
    val moduleSessionId: String,
    /** The target session that produced it. */
    val targetSessionId: String,
    /** The bootstrap stage the runtime was in when it reported. */
    val stage: String,
    /** The aggregate state of the runtime at the time of the report. */
    val state: SubsystemState,
    /** The worst failure present, or null when nothing failed. */
    val failureCode: RuntimeFailureCode? = null,
    /** When the runtime produced this heartbeat, in wall-clock milliseconds. */
    val timestampMillis: Long,
    /** The module version that produced it. */
    val moduleVersion: String,
    /** The target's version name, when it could be read. */
    val targetVersionName: String? = null,
) {
    init {
        require(packageName.isNotBlank()) { "a heartbeat needs a package" }
        require(processName.isNotBlank()) { "a heartbeat needs a process" }
        require(pid > 0) { "a heartbeat needs the process it came from" }
        require(stage.isNotBlank()) { "a heartbeat names the stage it came from" }
        require(timestampMillis > 0L) { "a heartbeat must be dated, or its age is unknowable" }
        require(state != SubsystemState.UNKNOWN) {
            "a heartbeat that reports UNKNOWN is not evidence of anything"
        }
        require(failureCode == null || state == SubsystemState.FAILED || state == SubsystemState.DEGRADED) {
            "a failure code is only meaningful on a subsystem that failed or degraded"
        }
    }

    /** The key this heartbeat is stored and compared under. Package *and* process. */
    val targetKey: String get() = "$packageName|$processName"

    /** How old this heartbeat is at [nowMillis]. */
    fun ageMillis(nowMillis: Long): Long = nowMillis - timestampMillis

    /** How old this heartbeat is, as one of the model's three ages. */
    fun freshnessAt(nowMillis: Long): HealthFreshness = HealthFreshness.at(timestampMillis, nowMillis)

    /**
     * Whether this heartbeat describes a different boot than [bootId].
     *
     * A heartbeat that predates a reboot describes a machine that no longer exists in the same
     * state, so it must not be aged by the same clock as one from this boot.
     */
    fun isFromBoot(bootId: String): Boolean = this.bootId == bootId

    /**
     * Whether [other] is this same reporting session.
     *
     * Used to tell a restart from a continuing process without a clock: a new process id or a
     * new target session means a new process, whatever the timestamp says.
     */
    fun isSameSessionAs(other: TargetHeartbeat): Boolean =
        pid == other.pid && targetSessionId == other.targetSessionId && bootId == other.bootId
}
