package com.wax.module.modern;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ModernInvocationEvidenceTest {
    @Test public void evidenceIsTargetScopedAndDoesNotAcceptUnknownPackages() {
        assertEquals("modern.feature.custom_time.last_invoked.com.whatsapp",
                ModernInvocationEvidence.timeKey("com.whatsapp"));
        assertEquals("modern.feature.custom_time.invocation_boot.com.whatsapp.w4b",
                ModernInvocationEvidence.bootKey("com.whatsapp.w4b"));
        assertThrows(IllegalArgumentException.class,
                () -> ModernInvocationEvidence.countKey("system_server"));
    }

    @Test public void installedButNotInvokedIsNeverReportedAsExecuted() {
        long now = 1_800_000_000_000L;
        long boot = now - 120_000L;
        assertEquals(ModernInvocationEvidence.Status.NOT_OBSERVED,
                ModernInvocationEvidence.classify(0, 0, 0, now, boot));
        assertEquals(ModernInvocationEvidence.Status.NOT_OBSERVED,
                ModernInvocationEvidence.classify(now - 100L, boot, 0, now, boot));
        assertEquals(ModernInvocationEvidence.Status.INVOKED_FRESH,
                ModernInvocationEvidence.classify(now - 100L, boot, 1, now, boot + 200L));
    }

    @Test public void staleClockAndRebootProofCannotBecomeFresh() {
        long now = 1_800_000_000_000L;
        long boot = now - 120_000L;
        assertEquals(ModernInvocationEvidence.Status.INVOKED_STALE,
                ModernInvocationEvidence.classify(now - 120_000L, boot, 2, now, boot));
        assertEquals(ModernInvocationEvidence.Status.DIFFERENT_BOOT,
                ModernInvocationEvidence.classify(now - 100L, boot, 2, now, boot + 100_000L));
        assertEquals(ModernInvocationEvidence.Status.CLOCK_MISMATCH,
                ModernInvocationEvidence.classify(now + 100L, boot, 2, now, boot));
    }
}
