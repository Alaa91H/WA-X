package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Test

class ModernManagerRuntimeStatusTest {
    @Test
    fun reportsAreOnlyFreshWhenCurrentAndBootMatched() {
        val now = 1_800_000_000_000L
        val boot = now - 600_000L
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.FRESH_BOOTSTRAP,
            ModernManagerRuntimeStatus.classify(now - 1000L, boot, now, boot + 100L),
        )
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.STALE_BOOTSTRAP,
            ModernManagerRuntimeStatus.classify(now - 91_000L, boot, now, boot),
        )
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.BOOT_MISMATCH,
            ModernManagerRuntimeStatus.classify(now - 1000L, boot, now, boot + 60_000L),
        )
    }

    @Test
    fun missingAndFutureReportsNeverProveInjection() {
        val now = 1_800_000_000_000L
        val boot = now - 600_000L
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.NOT_REPORTED,
            ModernManagerRuntimeStatus.classify(0L, boot, now, boot),
        )
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.CLOCK_MISMATCH,
            ModernManagerRuntimeStatus.classify(now + 100L, boot, now, boot),
        )
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.BOOT_MISMATCH,
            ModernManagerRuntimeStatus.classify(now - 1000L, 0L, now, boot),
        )
    }

    @Test
    fun freshAuthenticatedHeartbeatKeepsRunningTargetActiveAfterStartupExpires() {
        val now = 1_800_000_000_000L
        val elapsed = 800_000L
        val boot = now - elapsed
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.LIVE_HEARTBEAT,
            ModernManagerRuntimeStatus.classifyWithHeartbeat(
                now - 500_000L,
                boot,
                now,
                boot,
                elapsed - 45_000L,
                boot + 100L,
                elapsed,
            ),
        )
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.STALE_BOOTSTRAP,
            ModernManagerRuntimeStatus.classifyWithHeartbeat(
                now - 500_000L,
                boot,
                now,
                boot,
                elapsed - 160_000L,
                boot,
                elapsed,
            ),
        )
    }

    @Test
    fun heartbeatNeverClaimsLiveFromPreviousBootOrFutureUptime() {
        val now = 1_800_000_000_000L
        val elapsed = 800_000L
        val boot = now - elapsed
        val original = now - 500_000L
        val scenarios =
            listOf(
                Triple(elapsed - 20_000L, boot - 100_000L, elapsed),
                Triple(elapsed + 500L, boot, elapsed),
                Triple(0L, boot, elapsed),
                Triple(elapsed - 20_000L, 0L, elapsed),
            )
        scenarios.forEach { (last, recordedBoot, current) ->
            assertEquals(
                ModernManagerRuntimeStatus.Evidence.STALE_BOOTSTRAP,
                ModernManagerRuntimeStatus.classifyWithHeartbeat(
                    original,
                    boot,
                    now,
                    boot,
                    last,
                    recordedBoot,
                    current,
                ),
            )
        }
    }

    @Test
    fun heartbeatCannotRemainLiveAfterProcessStops() {
        val now = 1_800_000_000_000L
        val elapsed = 600_000L
        val boot = now - elapsed
        // Suspended target workers stop reporting: the Manager must not say live.
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.STALE_BOOTSTRAP,
            ModernManagerRuntimeStatus.classifyWithHeartbeat(
                now - 300_000L,
                boot,
                now,
                boot,
                elapsed - 150_001L,
                boot,
                elapsed,
            ),
        )
    }

    @Test
    fun freshBootstrapStillWorksBeforeFirstScheduledHeartbeat() {
        val now = 1_800_000_000_000L
        val boot = now - 100_000L
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.FRESH_BOOTSTRAP,
            ModernManagerRuntimeStatus.classifyWithHeartbeat(
                now - 1000L,
                boot,
                now,
                boot,
                0L,
                0L,
                100_000L,
            ),
        )
    }

    @Test
    fun lifecycleProofCannotBeInventedFromInstalledHook() {
        val now = 1_800_000_000_000L
        val stage = "modern.runtime.milestone.ATTACH_HOOK_INSTALLED.com.whatsapp"
        val found =
            ModernManagerRuntimeStatus.observedMilestones("com.whatsapp", now) { key ->
                if (key == stage) now - 1000L else 0L
            }
        assertEquals(listOf("ATTACH_HOOK_INSTALLED"), found)
        // Hook registration alone must not become a target heartbeat.
        assertEquals(
            ModernManagerRuntimeStatus.Evidence.NOT_REPORTED,
            ModernManagerRuntimeStatus.classify(0L, 0L, now, now - 500_000L),
        )
    }

    @Test
    fun lifecycleStagesDoNotLeakAcrossTargetsOrOldBoots() {
        val now = 1_800_000_000_000L
        val stages =
            ModernManagerRuntimeStatus.observedMilestones("com.whatsapp.w4b", now) { key ->
                when (key) {
                    "modern.runtime.milestone.MODULE_LOADED.com.whatsapp" -> now - 100L
                    "modern.runtime.milestone.MODULE_LOADED.com.whatsapp.w4b" -> now - 130_000L
                    "modern.runtime.milestone.PACKAGE_LOADED.com.whatsapp.w4b" -> now + 200L
                    else -> 0L
                }
            }
        assertEquals(emptyList<String>(), stages)
    }
}
