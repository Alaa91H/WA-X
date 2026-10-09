package com.wax.module.modern

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
        STALE_BOOTSTRAP,
        BOOT_MISMATCH,
        CLOCK_MISMATCH,
    }

    data class Target(
        val packageName: String,
        val evidence: Evidence,
        val customTimeInstallation: String?,
    )

    data class Snapshot(
        val connected: Boolean,
        val frameworkApi: Int?,
        val frameworkName: String?,
        val connectionProblem: String?,
        val targets: List<Target>,
    )

    private val targetNames = listOf("com.whatsapp", "com.whatsapp.w4b")

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

    /** Must be called from a background dispatcher: service preferences may invoke Binder. */
    fun inspect(): Snapshot {
        val framework = ModernFrameworkServiceBridge.currentState()
        if (!framework.connected) {
            return Snapshot(
                connected = false,
                frameworkApi = framework.apiVersion,
                frameworkName = framework.frameworkName,
                connectionProblem = framework.error ?: "SERVICE_NOT_CONNECTED",
                targets = targetNames.map { Target(it, Evidence.NOT_REPORTED, null) },
            )
        }
        return try {
            val prefs = ModernFrameworkServiceBridge.remotePreferences()
            val now = System.currentTimeMillis()
            val boot = now - SystemClock.elapsedRealtime()
            Snapshot(
                connected = prefs != null,
                frameworkApi = framework.apiVersion,
                frameworkName = framework.frameworkName,
                connectionProblem = if (prefs == null) "REMOTE_PREFERENCES_UNAVAILABLE" else null,
                targets =
                    targetNames.map { name ->
                        val timestamp = prefs?.getLong("modern_canary.last_bootstrap.$name", 0L) ?: 0L
                        val origin = prefs?.getLong("modern_canary.boot_epoch.$name", 0L) ?: 0L
                        Target(
                            packageName = name,
                            evidence = classify(timestamp, origin, now, boot),
                            customTimeInstallation = prefs?.getString("modern.feature.custom_time.state.$name", null),
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
                targets = targetNames.map { Target(it, Evidence.NOT_REPORTED, null) },
            )
        }
    }
}
