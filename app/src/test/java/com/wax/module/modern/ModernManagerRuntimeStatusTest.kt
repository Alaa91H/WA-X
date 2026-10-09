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
}
