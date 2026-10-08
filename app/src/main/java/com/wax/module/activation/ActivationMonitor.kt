package com.wax.module.activation

import android.content.Context
import android.os.SystemClock
import com.wax.module.health.BootIdentity
import java.io.File

/**
 * The Manager's view of every target's activation: observation in, status out.
 *
 * This is the only place the Manager touches the activation model, so the wiring between
 * "what was observed" and "what may be said" is one object rather than a scattering of `when`
 * branches inside a Fragment. A Fragment that computed its own verdict would be exactly the
 * place a verdict could be computed two different ways.
 *
 * Boot identity comes from the same derivation the runtime uses
 * ([com.wax.module.health.BootIdentity]), which is what lets a heartbeat left over from before
 * a reboot be recognised as belonging to a machine state that no longer exists.
 */
class ActivationMonitor(
    private val store: ActivationStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentBoot: () -> String = { currentBootId() },
) {
    /**
     * The status of [packageName], from the filed heartbeat plus what the caller observed.
     *
     * [installed] and [process] are parameters rather than queries because observing a process
     * is a platform call the caller may not be allowed to make, and a monitor that quietly
     * reported "not running" when it could not check would be inventing evidence. Passing them
     * in keeps "I did not look" distinguishable from "I looked and it is not there".
     */
    fun status(
        packageName: String,
        installed: Boolean,
        process: TargetProcessObservation,
        legacySelfHookSignal: Boolean,
    ): ActivationStatus =
        ActivationStatusResolver.resolve(
            observation =
                ActivationObservation(
                    packageName = packageName,
                    installed = installed,
                    process = process,
                    heartbeat = heartbeatFor(packageName),
                    legacySelfHookSignal = legacySelfHookSignal,
                ),
            nowMillis = now(),
            currentBootId = currentBoot(),
        )

    /**
     * Files a heartbeat received from inside a target's process.
     *
     * A heartbeat for a different target than expected is still filed: the runtime is the
     * authority on which package it is inside, and the Manager's own bookkeeping is not.
     */
    fun accept(heartbeat: TargetHeartbeat?) {
        if (heartbeat == null) return
        store.write(heartbeat)
    }

    /**
     * The heartbeat for the target's *main process*, if any.
     *
     * [ActivationStore] writes under `package|process` (see [TargetHeartbeat.targetKey]).
     * Reading just the package never found that record, so the Home screen stayed UNKNOWN
     * even after the runtime successfully replied to the Manager's probe.
     *
     * Do not use a secondary process' heartbeat to mark the main process READY: a
     * background process can keep running when the main WhatsApp process is gone.
     */
    fun heartbeatFor(packageName: String): TargetHeartbeat? = store.read("$packageName|$packageName")

    companion object {
        /** The Manager's monitor, reading heartbeats from its own storage. */
        fun forContext(context: Context): ActivationMonitor = ActivationMonitor(storeFor(context))

        /** Where the Manager keeps filed heartbeats, inside its own storage. */
        fun storeFor(context: Context): ActivationStore = ActivationStore(File(context.filesDir, DIRECTORY))

        /** The directory filed heartbeats live in. */
        const val DIRECTORY: String = "activation"

        /**
         * The identity of the current boot, derived rather than stored.
         *
         * Stored boot ids go stale on their own - a file written before a reboot still contains
         * the old one - so the current value is computed from the two clocks Android keeps
         * consistent with a reboot. This is the same derivation the runtime performs, which is
         * why both ends can compare the strings they produce.
         */
        fun currentBootId(): String =
            BootIdentity.of(
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                nowMillis = System.currentTimeMillis(),
            )
    }
}
