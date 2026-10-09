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

    @Test public void runtimeHeartbeatAcceptsOnlyFixedAliveEvidence() {
        assertTrue(ModernTargetTelemetryProvider.isSupportedRuntimeHeartbeatValue("ALIVE"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedRuntimeHeartbeatValue("INSTALLED"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedRuntimeHeartbeatValue("BOOTSTRAP"));
        assertFalse(ModernTargetTelemetryProvider.isSupportedRuntimeHeartbeatValue(null));
    }

    @Test public void refusesUnrelatedPackagesAndArbitraryEventNames() {
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.whatsapp:push", 10482, new String[] {"com.whatsapp"}));
        assertFalse(ModernTargetTelemetryProvider.isAuthorizedSender(
                "com.other.app", 10482, new String[] {"com.other.app"}));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_BOOTSTRAP));
        assertTrue(ModernTargetTelemetryProvider.isSupportedEvent(
                ModernTargetTelemetryProvider.EVENT_RUNTIME_HEARTBEAT));
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

    @Test public void settingsWriteAllowlistCoversOnlyWiredAdapters() {
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey(
                "modern.feature.custom_time.enabled"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("removeforwardlimit"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("freezelastseen"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("dndmode"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("segundos"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("ghostmode"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("EXECUTE_CODE"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey(null));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey(""));
    }

    @Test public void controlCenterReadExposesOnlyAllowlistedKeys() {
        // The embedded Control Center may read exactly these keys and nothing else.
        assertTrue(ModernTargetTelemetryProvider.CONTROL_CENTER_EVIDENCE_KEYS.length > 0);
        for (String key : ModernTargetTelemetryProvider.CONTROL_CENTER_EVIDENCE_KEYS) {
            assertTrue(key.startsWith("modern.feature."));
        }
        assertTrue(ModernTargetTelemetryProvider.CONTROL_CENTER_PREFERENCE_KEYS.length > 0);
        for (String key : ModernTargetTelemetryProvider.CONTROL_CENTER_PREFERENCE_KEYS) {
            assertFalse(key.startsWith("segundos"));
            assertFalse(key.startsWith("ampm"));
        }
    }
}
