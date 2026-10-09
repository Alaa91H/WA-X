package com.wax.module.modern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Pure identity/event-contract checks; real Binder delivery still needs a rooted device. */
public final class ModernTargetTelemetryProviderTest {
    @Test public void onlyWhatsappLinuxUidCanPublishItsOwnReport() {
        assertTrue(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp", 10482, new String[] {"com.whatsapp"}));
        assertTrue(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp.w4b", 10483, new String[] {"com.whatsapp.w4b"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp", 10483, new String[] {"com.whatsapp.w4b"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp", 10480, new String[] {"com.wax.module"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp", 0, new String[] {"com.whatsapp"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp", 10482, null));
    }

    @Test public void menuEvidenceValuesAreFixedAndDoNotLeakAppData() {
        assertTrue(ModernTargetTelemetryProvider.isSupportedMenuHomeState("INSTALLED"));
        assertTrue(ModernTargetTelemetryProvider.isSupportedMenuHomeState("ITEM_ADDED"));
        assertTrue(ModernTargetTelemetryProvider.isSupportedMenuHomeState("MENU_METHOD_MISSING"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedMenuHomeState("EXECUTE_CODE"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedMenuHomeState(null));
    }

    @Test public void refusesUnrelatedPackagesAndArbitraryEventNames() {
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp:push", 10482, new String[] {"com.whatsapp"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.other.app", 10482, new String[] {"com.other.app"}));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_BOOTSTRAP));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_CUSTOM_TIME));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_FREEZE_LAST_SEEN));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_DND_MODE));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_SHARE_LIMIT));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_MENU_HOME));
        assertFalse(ModernTargetTelemetryProvider.isSupportedEvent("EXECUTE_CODE"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedEvent(null));
    }
}
