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
