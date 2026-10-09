package com.wax.module.modern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ModernTargetPolicyTest {
    @Test public void acceptsBothPrimaryTargets() {
        assertTrue(ModernTargetPolicy.isMainTarget("com.whatsapp", "com.whatsapp", true));
        assertTrue(ModernTargetPolicy.isMainTarget("com.whatsapp.w4b", "com.whatsapp.w4b", true));
    }

    @Test public void neverLoadsIntoBackgroundOrFrameworkProcesses() {
        assertFalse(ModernTargetPolicy.isMainTarget("com.whatsapp:push", "com.whatsapp", true));
        assertFalse(ModernTargetPolicy.isMainTarget("system_server", "android", true));
        assertFalse(ModernTargetPolicy.isMainTarget("com.wax.module", "com.wax.module", true));
    }

    @Test public void avoidsRepeatedPackageCallback() {
        assertFalse(ModernTargetPolicy.isMainTarget("com.whatsapp", "com.whatsapp", false));
        assertFalse(ModernTargetPolicy.isMainTarget(null, "com.whatsapp", true));
    }
}
