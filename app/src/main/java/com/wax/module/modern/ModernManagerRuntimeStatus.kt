package com.wax.module.modern

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Runtime truth for the actual com.wax.module package's optional API 102 build.
 * Never interpret a framework service handshake as proof of WhatsApp feature readiness.
 */
object ModernManagerRuntimeStatus {
    enum class Evidence {
        NOT_REPORTED,
        FRESH_BOOTSTRAP,
        LIVE_HEARTBEAT,
        STALE_BOOTSTRAP,
        BOOT_MISMATCH,
        CLOCK_MISMATCH,
    }

    data class Target(
        val packageName: String,
        val evidence: Evidence,
        val customTimeInstallation: String?,
        val shareLimitInstallation: String?,
        val freezeInstallation: String?,
        val dndInstallation: String?,
        val bootstrapMilestones: List<String> = emptyList(),
        val menuInstallation: String? = null,
    )

    data class Snapshot(
        val connected: Boolean,
        val frameworkApi: Int?,
        val frameworkName: String?,
        val connectionProblem: String?,
        val targets: List<Target>,
    )

    private val targetNames = listOf("com.whatsapp", "com.whatsapp.w4b")

    // Individual timestamped milestones cannot overwrite each other out of order.
    // They are diagnostics ONLY; a milestone never sets evidence=FRESH_BOOTSTRAP.
    private val bootstrapMilestones =
        listOf(
            "MODULE_LOADED",
            "ATTACH_HOOK_INSTALLED",
            "PACKAGE_LOADED",
            "ATTACH_OBSERVED",
            "HEARTBEAT_WRITE_CONFIRMED",
            "HEARTBEAT_WRITE_REJECTED",
            "ATTACH_HOOK_FAILED",
        )

    fun observedMilestones(
        targetName: String,
        nowMillis: Long,
        timestampOf: (String) -> Long,
    ): List<String> =
        bootstrapMilestones.filter { stage ->
            val at = timestampOf("modern.runtime.milestone.$stage.$targetName")
            at > 0 && at <= nowMillis && nowMillis - at <= 120_000L
        }

    fun classify(
        lastReportMillis: Long,
        recordedBootMillis: Long,
        currentMillis: Long,
        currentBootMillis: Long,
    ): Evidence {
        if (lastReportMillis <= 0L) return Evidence.NOT_REPORTED
        if (lastReportMillis > currentMillis) return Evidence.CLOCK_MISMATCH
        if (currentMillis - lastReportMillis > 90_000L) return Evidence.STALE_BOOTSTRAP
        if (recordedBootMillis <= 0L || currentBootMillis <= 0L) return Evidence.BOOT_MISMATCH
        val delta =
            if (recordedBootMillis >= currentBootMillis) {
                recordedBootMillis - currentBootMillis
            } else {
                currentBootMillis - recordedBootMillis
            }
        return if (delta <= 5_000L) Evidence.FRESH_BOOTSTRAP else Evidence.BOOT_MISMATCH
    }

    /**
     * Only a fresh heartbeat sent from WhatsApp's real UID proves recent process activity.
     * A historical successful bootstrap must never be interpreted as current liveness.
     * Use monotonic uptime as the freshness clock and a boot-epoch guard for restarts.
     */
    fun classifyWithHeartbeat(
        lastReportMillis: Long,
        recordedBootMillis: Long,
        currentMillis: Long,
        currentBootMillis: Long,
        heartbeatElapsedMillis: Long,
        heartbeatBootMillis: Long,
        currentElapsedMillis: Long,
    ): Evidence {
        val bootstrap = classify(lastReportMillis, recordedBootMillis, currentMillis, currentBootMillis)
        if (heartbeatElapsedMillis <= 0L || heartbeatBootMillis <= 0L) return bootstrap
        if (currentElapsedMillis < heartbeatElapsedMillis) return bootstrap
        if (currentElapsedMillis - heartbeatElapsedMillis > 150_000L) return bootstrap
        val bootDifference =
            if (heartbeatBootMillis >= currentBootMillis) {
                heartbeatBootMillis - currentBootMillis
            } else {
                currentBootMillis - heartbeatBootMillis
            }
        if (bootDifference > 5_000L) return bootstrap
        return Evidence.LIVE_HEARTBEAT
    }

    /** Target reports live in Manager-owned preferences, not read-only Xposed target prefs. */
    fun inspect(context: Context): Snapshot {
        val framework = ModernFrameworkServiceBridge.currentState()
        if (!framework.connected) {
            return Snapshot(
                connected = false,
                frameworkApi = framework.apiVersion,
                frameworkName = framework.frameworkName,
                connectionProblem = framework.error ?: "SERVICE_NOT_CONNECTED",
                targets = targetNames.map { Target(it, Evidence.NOT_REPORTED, null, null, null, null) },
            )
        }
        return try {
            val prefs = context.getSharedPreferences(ModernTargetTelemetryProvider.LOCAL_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val boot = now - elapsed
            Snapshot(
                connected = true,
                frameworkApi = framework.apiVersion,
                frameworkName = framework.frameworkName,
                connectionProblem = null,
                targets =
                    targetNames.map { name ->
                        val timestamp = prefs.getLong("modern.bootstrap.last.$name", 0L)
                        val origin = prefs.getLong("modern.bootstrap.boot.$name", 0L)
                        Target(
                            packageName = name,
                            evidence =
                                classifyWithHeartbeat(
                                    timestamp,
                                    origin,
                                    now,
                                    boot,
                                    prefs.getLong("modern.heartbeat.elapsed.$name", 0L),
                                    prefs.getLong("modern.heartbeat.boot.$name", 0L),
                                    elapsed,
                                ),
                            customTimeInstallation = prefs.getString("modern.feature.custom_time.state.$name", null),
                            shareLimitInstallation = prefs.getString("modern.feature.share_limit.state.$name", null),
                            freezeInstallation = prefs.getString("modern.feature.freeze_last_seen.state.$name", null),
                            dndInstallation = prefs.getString("modern.feature.dnd_mode.state.$name", null),
                            bootstrapMilestones =
                                observedMilestones(name, now) { key -> prefs.getLong(key, 0L) },
                            menuInstallation = prefs.getString("modern.feature.menu_home.state.$name", null),
                        )
                    },
            )
        } catch (error: RuntimeException) {
            Log.w("WA-X Modern", "Modern runtime preference read failed", error)
            Snapshot(
                connected = false,
                frameworkApi = framework.apiVersion,
                frameworkName = framework.frameworkName,
                connectionProblem = "PREFERENCES_READ_FAILED",
                targets = targetNames.map { Target(it, Evidence.NOT_REPORTED, null, null, null, null) },
            )
        }
    }
}
