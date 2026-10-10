package com.wax.module.modern;

import static org.junit.Assert.assertEquals;
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
        // Typing/recording privacy is migrated, so its switches are writable.
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("ghostmode"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("ghostmode_t"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("ghostmode_r"));
        // A formatting sub-key and any unmigrated switch stay refused.
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("segundos"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("show_dndmode"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("viewonce"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey("EXECUTE_CODE"));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey(null));
        assertFalse(ModernTargetTelemetryProvider.isWritableSettingKey(""));
    }

    @Test public void archiveModeIsReadAsAStringNotABoolean() {
        // The archived-chat mode is a three-state string; reading it through
        // the boolean path would silently report every mode as "off".
        assertEquals("typearchive", ModernTargetTelemetryProvider.CONTROL_CENTER_MODE_KEY);
        assertEquals("0", ModernTargetTelemetryProvider.MODE_DISABLED);
        assertEquals("1", ModernTargetTelemetryProvider.MODE_CLICK_TIMES);
        assertEquals("2", ModernTargetTelemetryProvider.MODE_HOLD_TITLE);
    }

    @Test public void favouritesAreValidatedBeforePersistence() {
        assertTrue(ModernTargetTelemetryProvider.isValidFavorites(""));
        assertTrue(ModernTargetTelemetryProvider.isValidFavorites("custom_time,dnd_mode"));
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites(null));
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites("custom_time,,dnd_mode"));
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites("Custom Time"));
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites("custom time"));
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites("../etc/passwd"));
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            tooLong.append("aaaaaaaaaa,");
        }
        assertFalse(ModernTargetTelemetryProvider.isValidFavorites(tooLong.toString()));
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
    /** Simulates existing Manager values without requiring an Android device. */
    private static android.content.SharedPreferences preferences(Object... entries) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], entries[i + 1]);
        }
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),
                new Class<?>[] {android.content.SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) return values;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test public void listModesRemainReadableAndRetainExistingSelections() {
        android.content.SharedPreferences prefs = preferences(
                "typearchive", "2", "antirevoke", "1");
        assertEquals("2", ModernTargetTelemetryProvider.readMode(prefs, "typearchive"));
        assertEquals("1", ModernTargetTelemetryProvider.readMode(prefs, "antirevoke"));
        assertTrue(ModernTargetTelemetryProvider.isWritableSettingKey("antirevoke"));
    }

    @Test public void oldBooleanModeWritesAreSafelyInterpreted() {
        android.content.SharedPreferences prefs = preferences(
                "antirevoke", true, "typearchive", false, "unrelated", "value");
        assertEquals("1", ModernTargetTelemetryProvider.readMode(prefs, "antirevoke"));
        assertEquals("0", ModernTargetTelemetryProvider.readMode(prefs, "typearchive"));
        assertEquals("0", ModernTargetTelemetryProvider.readMode(prefs, "absent"));
        assertFalse(ModernTargetTelemetryProvider.readBoolean(prefs, "unrelated"));
        assertTrue(ModernTargetTelemetryProvider.readBoolean(prefs, "antirevoke"));
    }

    @Test public void booleanSnapshotNeverIteratesLegacyStringModes() {
        java.util.List<String> keys = java.util.Arrays.asList(
                ModernTargetTelemetryProvider.CONTROL_CENTER_PREFERENCE_KEYS);
        assertFalse(keys.contains("typearchive"));
        assertFalse(keys.contains("antirevoke"));
        assertTrue(keys.contains("hideread"));
        assertTrue(keys.contains("sendstatusseenonreply"));
        assertTrue(ModernTargetTelemetryProvider.readBoolean(
                preferences("hideread", true), "hideread"));
    }
}
