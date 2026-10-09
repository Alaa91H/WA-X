package com.wax.module.modern

import android.content.Intent
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

    @Test fun pickerResultOnlyFillsMissingExtras() {
        val intent = Intent()
        intent.putExtra("key", "already-set")
        ModernActivityControllerFeature.applyPickerResult(intent, "from-wax")
        assertEquals("already-set", intent.getStringExtra("key"))
        assertTrue(intent.getStringArrayListExtra("contacts")!!.isEmpty())
        assertTrue(intent.getSerializableExtra("picker_contacts") != null)
    }

    @Test fun pickerIntentCarriesTheManagerKeyAndPickerMode() {
        val intent = ModernActivityControllerFeature.buildPickerIntent(
            "com.whatsapp", "com.whatsapp.settings.About", null,
        )
        assertEquals("com.whatsapp", intent.component?.packageName)
        assertEquals("com.whatsapp.settings.About", intent.component?.className)
        assertTrue(intent.getBooleanExtra("picker_mode", false))
        assertEquals("", intent.getStringExtra("key"))
    }

    @Test fun authWindowStartsClosed() {
        // Safety property: the bypass must never be armed outside a round trip.
        assertFalse(ModernActivityControllerFeature.isAuthBypassed())
    }
}