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
}
