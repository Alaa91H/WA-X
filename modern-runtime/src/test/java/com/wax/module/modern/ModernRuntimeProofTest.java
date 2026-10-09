package com.wax.module.modern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public final class ModernRuntimeProofTest {
    @Test public void realTargetHasOwnEvidenceKey() {
        assertEquals("modern_canary.last_bootstrap.com.whatsapp",
                ModernRuntimeProof.heartbeatKey("com.whatsapp"));
        assertEquals("modern_canary.last_bootstrap.com.whatsapp.w4b",
                ModernRuntimeProof.heartbeatKey("com.whatsapp.w4b"));
        assertEquals("modern_canary.last_process.com.whatsapp.w4b",
                ModernRuntimeProof.processKey("com.whatsapp.w4b"));
        assertThrows(IllegalArgumentException.class,
                () -> ModernRuntimeProof.heartbeatKey("system_server"));
    }

    @Test public void freshReportMustMatchCurrentBootWithoutMillisecondRigidity() {
        long now = 1_800_000_000_000L;
        long boot = now - 600_000L;
        assertEquals(ModernRuntimeProof.State.FRESH_TARGET_REPORT,
                ModernRuntimeProof.classify(now - 1000L, now, boot, boot + 90L));
        assertEquals(ModernRuntimeProof.State.BOOT_MISMATCH,
                ModernRuntimeProof.classify(now - 1000L, now, boot, boot + 90_000L));
        assertEquals(ModernRuntimeProof.State.BOOT_MISMATCH,
                ModernRuntimeProof.classify(now - 1000L, now, 0L, boot));
        assertEquals("modern_canary.boot_epoch.com.whatsapp",
                ModernRuntimeProof.bootEpochKey("com.whatsapp"));
    }

    @Test public void neverEquatesAbsentOrOldReportWithFreshInjection() {
        long now = 1_000_000L;
        assertEquals(ModernRuntimeProof.State.UNREPORTED,
                ModernRuntimeProof.classify(0L, now));
        assertEquals(ModernRuntimeProof.State.FRESH_TARGET_REPORT,
                ModernRuntimeProof.classify(now - 5_000L, now));
        assertEquals(ModernRuntimeProof.State.STALE_TARGET_REPORT,
                ModernRuntimeProof.classify(now - 91_000L, now));
        assertEquals(ModernRuntimeProof.State.CLOCK_MISMATCH,
                ModernRuntimeProof.classify(now + 1L, now));
    }
}
