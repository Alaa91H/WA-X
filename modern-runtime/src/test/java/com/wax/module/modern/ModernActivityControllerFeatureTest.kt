package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the contact-picker relay; real rounds need the target app. */
class ModernActivityControllerFeatureTest {
    @Test fun authAnchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals(
            "privacy_fingerprint_enabled",
            ModernActivityControllerFeature.ANCHOR_LOCKED_AUTH,
        )
        assertEquals("app_lock_auth_needed", ModernActivityControllerFeature.ANCHOR_AUTH_NEEDED)
    }

    @Test fun settingsActivitySuffixMatchesTheManagerLauncherContract() {
        assertTrue(
            ModernActivityControllerFeature.isSettingsNotificationsActivity(
                "com.whatsapp.SettingsNotifications",
            ),
        )
        assertTrue(
            ModernActivityControllerFeature.isSettingsNotificationsActivity(
                "com.whatsapp.settings.ui.SettingsNotifications",
            ),
        )
        assertFalse(
            ModernActivityControllerFeature.isSettingsNotificationsActivity(
                "com.whatsapp.home.ui.HomeActivity",
            ),
        )
        assertFalse(ModernActivityControllerFeature.isSettingsNotificationsActivity(null))
    }

    @Test fun resultNeverOverwritesWhatThePickerAlreadySet() {
        val alreadyComplete = setOf("key", "contacts", "picker_contacts")
        assertEquals(
            emptyList<String>(),
            ModernActivityControllerFeature.extrasToFill(alreadyComplete, "from-wax"),
        )
    }

    @Test fun missingResultExtrasAreFilled() {
        assertEquals(
            listOf("key", "contacts", "picker_contacts"),
            ModernActivityControllerFeature.extrasToFill(emptySet(), "from-wax"),
        )
        assertEquals(
            listOf("contacts", "picker_contacts"),
            ModernActivityControllerFeature.extrasToFill(setOf("key"), "from-wax"),
        )
    }

    @Test fun aNullKeyNeverCreatesAnEmptyKeyExtra() {
        assertEquals(
            listOf("contacts", "picker_contacts"),
            ModernActivityControllerFeature.extrasToFill(emptySet(), null),
        )
    }

    @Test fun authWindowStartsClosed() {
        // Safety property: the bypass must never be armed outside a round trip.
        assertFalse(ModernActivityControllerFeature.isAuthBypassed())
    }

    @Test fun pickerRequestCodeMatchesTheManagerPreference() {
        // Must equal ContactPickerPreference.REQUEST_CONTACT_PICKER (0xff2515).
        assertEquals(0xff2515, ModernActivityControllerFeature.REQUEST_CONTACT_PICKER)
    }

    @Test fun outcomeSetCoversInstallReplayAndResolutionFailures() {
        val outcomes = ModernActivityControllerFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "INSTALLED", "ALREADY_INSTALLED", "APPLICATION_UNAVAILABLE",
            "AUTH_RESOLVER_MISSING", "AUTH_RESOLVER_AMBIGUOUS",
            "SETTINGS_ACTIVITY_UNRESOLVED", "ERROR",
        )))
    }
}