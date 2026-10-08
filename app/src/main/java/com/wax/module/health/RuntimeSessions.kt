package com.wax.module.health

import java.util.UUID

/**
 * The three identities every health record carries.
 *
 * A runtime that cannot name the session a fact came from cannot answer the only question
 * that matters when something breaks: "is this state now, or is it the state of the
 * process that died before I opened the app?". [bootId] separates device reboots,
 * [moduleSessionId] separates one load of the module in one process, and [targetSessionId]
 * separates one run of the target application from the next — including the case where the
 * user force-stopped WhatsApp and it came back with a new process while the module was
 * never reloaded.
 *
 * None of these values is derived from anything that identifies the user: they are
 * generated, not observed, and the worst a leak of one could reveal is that a session
 * existed.
 */
data class RuntimeSessions(
    val bootId: String,
    val moduleSessionId: String,
    val targetSessionId: String,
) {
    /** Whether every identity is present. */
    val isComplete: Boolean
        get() = bootId != NO_ID && moduleSessionId != NO_ID && targetSessionId != NO_ID

    companion object {
        /** Stand-in used before an identity exists. Never a valid session. */
        const val NO_ID: String = "none"

        /** A session with no identity yet. */
        fun unknown(): RuntimeSessions = RuntimeSessions(NO_ID, NO_ID, NO_ID)

        /**
         * Creates a session for [bootId] using [newId] for the two per-process identities.
         *
         * [newId] is injected so a test can make identities deterministic; production
         * callers get a random one.
         */
        fun create(
            bootId: String,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): RuntimeSessions {
            val normalizedBoot = bootId.ifBlank { NO_ID }
            return RuntimeSessions(
                bootId = normalizedBoot,
                moduleSessionId = newId(),
                targetSessionId = newId(),
            )
        }
    }
}

/**
 * Identifies a boot without reading anything that identifies the device.
 *
 * A boot's own wall-clock start is recoverable as `now - elapsedRealtime`, which is the
 * same value for every process on the device and changes only when the device reboots. It
 * is recorded as a number, so it is comparable but says nothing about the user.
 */
object BootIdentity {
    private const val PREFIX = "boot-"
    private const val MIN_REAL_BOOT_EPOCH_MILLIS = 946_684_800_000L
    private const val MAX_CLOCK_SAMPLING_SKEW_MILLIS = 5_000L

    /** The boot identity for a device whose uptime is [elapsedRealtimeMillis] at [nowMillis]. */
    fun of(
        elapsedRealtimeMillis: Long,
        nowMillis: Long,
    ): String = PREFIX + (nowMillis - elapsedRealtimeMillis).coerceAtLeast(0L)

    /**
     * Whether two independently sampled boot identities describe the same reboot.
     *
     * Wall time and elapsedRealtime are read separately, so two processes cannot reliably
     * produce the exact same millisecond timestamp. Comparing the generated strings for
     * equality rejected valid runtime heartbeats even when the Manager had received them.
     * Only realistic wall-clock boot timestamps may use this bounded sampling tolerance:
     * synthetic test ids, absent ids, and two genuinely different boots stay distinct.
     */
    fun isSameBoot(
        reported: String,
        current: String,
    ): Boolean {
        if (reported == current) return true

        fun bootTime(id: String): Long? =
            id
                .takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.toLongOrNull()
                ?.takeIf { it >= MIN_REAL_BOOT_EPOCH_MILLIS }

        val reportedTime = bootTime(reported) ?: return false
        val currentTime = bootTime(current) ?: return false
        val difference = if (reportedTime >= currentTime) reportedTime - currentTime else currentTime - reportedTime
        return difference <= MAX_CLOCK_SAMPLING_SKEW_MILLIS
    }

    /** The unknown boot. */
    fun unknown(): String = RuntimeSessions.NO_ID
}
