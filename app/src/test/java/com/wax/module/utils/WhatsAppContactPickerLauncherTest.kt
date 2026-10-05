package com.wax.module.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppContactPickerLauncherTest {
    @Test
    fun thePreferredAboutCandidateIsRecognised() {
        assertTrue(WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.settings.About"))
    }

    @Test
    fun theAlternateAboutCandidateIsRecognised() {
        assertTrue(
            WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.settings.ui.About"),
        )
    }

    @Test
    fun anyAboutSuffixIsAcceptedAsAFallback() {
        assertTrue(WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.About"))
        assertTrue(WhatsAppContactPickerLauncher.isAboutActivity("com.other.app.About"))
    }

    @Test
    fun anAboutPredicateIsCaseSensitive() {
        // Activity names are matched exactly; case folding here would accept classes
        // that do not exist.
        assertFalse(WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.settings.about"))
    }

    @Test
    fun anAboutPredicateRejectsAPrefixMatch() {
        assertFalse(WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.settings.AboutExtra"))
    }

    @Test
    fun anUnrelatedActivityIsNotAbout() {
        assertFalse(WhatsAppContactPickerLauncher.isAboutActivity("com.whatsapp.settings.Home"))
    }

    @Test
    fun aNullOrEmptyActivityIsNotAbout() {
        assertFalse(WhatsAppContactPickerLauncher.isAboutActivity(null))
        assertFalse(WhatsAppContactPickerLauncher.isAboutActivity(""))
    }

    @Test
    fun everyKnownNotificationSettingsCandidateIsRecognised() {
        assertTrue(
            WhatsAppContactPickerLauncher.isSettingsNotificationsActivity(
                "com.whatsapp.SettingsNotifications",
            ),
        )
        assertTrue(
            WhatsAppContactPickerLauncher.isSettingsNotificationsActivity(
                "com.whatsapp.settings.SettingsNotifications",
            ),
        )
        assertTrue(
            WhatsAppContactPickerLauncher.isSettingsNotificationsActivity(
                "com.whatsapp.settings.ui.SettingsNotifications",
            ),
        )
    }

    @Test
    fun anUnrelatedActivityIsNotNotificationSettings() {
        assertFalse(
            WhatsAppContactPickerLauncher.isSettingsNotificationsActivity("com.whatsapp.Settings"),
        )
    }

    @Test
    fun aNullOrEmptyActivityIsNotNotificationSettings() {
        assertFalse(WhatsAppContactPickerLauncher.isSettingsNotificationsActivity(null))
        assertFalse(WhatsAppContactPickerLauncher.isSettingsNotificationsActivity(""))
    }

    @Test
    fun businessIsLabelledDistinctlyFromWhatsApp() {
        assertTrue(
            WhatsAppContactPickerLauncher
                .getPackageLabel("com.whatsapp.w4b")
                .toString() == "WhatsApp Business",
        )
        assertTrue(
            WhatsAppContactPickerLauncher
                .getPackageLabel("com.whatsapp")
                .toString() == "WhatsApp",
        )
    }
}
