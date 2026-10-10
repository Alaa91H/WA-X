package com.wax.module.modern

import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract across the embedded panel, the authenticated Manager provider and API102 relay. */
class ModernControlCenterPreferenceContractTest {
    @Test
    fun everyWiredToggleCanBeSavedReadAndRelayed() {
        val booleans = ModernTargetTelemetryProvider.CONTROL_CENTER_PREFERENCE_KEYS.toSet()
        for (entry in ModernControlCenterCatalog.wired) {
            val key = entry.preferenceKey
            assertTrue("Missing relay observation for $key", ModernRuntimePreferenceRelay.observes(key))
            assertTrue("No live change notification for $key", ModernRuntimePreferenceRelay.affectsControlCenter(key))
            assertTrue(
                "Missing authenticated write for $key",
                key == "typearchive" || ModernTargetTelemetryProvider.isWritableSettingKey(key),
            )
            assertTrue(
                "Missing state read for $key",
                key in booleans || key == "typearchive" || key == "antirevoke",
            )
        }
    }

    @Test
    fun favoritesAndUnknownPreferenceChangesNotifyTheEmbeddedPanel() {
        assertTrue(ModernRuntimePreferenceRelay.affectsControlCenter(ModernControlCenterCatalog.FAVORITES_KEY))
        assertTrue(ModernRuntimePreferenceRelay.affectsControlCenter(null))
        org.junit.Assert.assertFalse(ModernRuntimePreferenceRelay.affectsControlCenter("unrelated_whatsapp_key"))
    }

    @Test
    fun malformedLegacyModeValuesAreNormalizedWithoutChangingSelections() {
        org.junit.Assert.assertEquals("1", ModernRuntimePreferenceRelay.legacyMode(true))
        org.junit.Assert.assertEquals("0", ModernRuntimePreferenceRelay.legacyMode(false))
        org.junit.Assert.assertEquals("2", ModernRuntimePreferenceRelay.legacyMode("2"))
        org.junit.Assert.assertEquals("1", ModernRuntimePreferenceRelay.legacyMode("1"))
        org.junit.Assert.assertEquals("0", ModernRuntimePreferenceRelay.legacyMode(null))
    }
}
