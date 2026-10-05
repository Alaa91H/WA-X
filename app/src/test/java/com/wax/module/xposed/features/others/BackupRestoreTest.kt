package com.wax.module.xposed.features.others

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTest {
    @Test
    fun theGoogleDriveActivityIsRecognised() {
        assertTrue(BackupRestore.isGoogleDriveActivity("GoogleDriveSettings"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(BackupRestore.isGoogleDriveActivity("googledrivesettings"))
        assertTrue(BackupRestore.isGoogleDriveActivity("GOOGLEDRIVE"))
    }

    @Test
    fun tokenOrderDoesNotMatter() {
        assertTrue(BackupRestore.isGoogleDriveActivity("DriveGoogleActivity"))
    }

    @Test
    fun anActivityMissingTheGoogleTokenIsRejected() {
        assertFalse(BackupRestore.isGoogleDriveActivity("DriveSettings"))
    }

    @Test
    fun anActivityMissingTheDriveTokenIsRejected() {
        assertFalse(BackupRestore.isGoogleDriveActivity("GoogleSettings"))
    }

    @Test
    fun anUnrelatedActivityIsRejected() {
        assertFalse(BackupRestore.isGoogleDriveActivity("SettingsActivity"))
    }

    @Test
    fun aNullNameIsRejected() {
        assertFalse(BackupRestore.isGoogleDriveActivity(null))
    }

    @Test
    fun anEmptyNameIsRejected() {
        assertFalse(BackupRestore.isGoogleDriveActivity(""))
    }

    @Test
    fun theMenuItemIdIsStable() {
        // The id doubles as the duplicate-injection guard, so it must not drift.
        assertTrue(BackupRestore.MENU_ITEM_ID == 10001)
    }

    @Test
    fun theRestoreActionIsStable() {
        assertTrue(
            BackupRestore.ACTION_RESTORE_ONE_TIME_SETUP == "action_show_restore_one_time_setup",
        )
    }
}
