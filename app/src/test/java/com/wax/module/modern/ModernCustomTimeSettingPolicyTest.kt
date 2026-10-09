package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernCustomTimeSettingPolicyTest {
    @Test
    fun featureIsNeverEnabledByFormattingPreferencesAlone() {
        val settings =
            ModernCustomTimeSettingPolicy.from(
                enabled = false,
                seconds = true,
                amPm = true,
                template = "Clock: [TIME]",
            )
        assertFalse(settings.enabled)
        assertTrue(settings.seconds)
        assertTrue(settings.amPm)
        assertEquals("Clock: [TIME]", settings.template)
    }

    @Test
    fun explicitOptInPreservesUserTimeFormatting() {
        val settings =
            ModernCustomTimeSettingPolicy.from(
                enabled = true,
                seconds = false,
                amPm = true,
                template = "Today [TIME]",
            )
        assertTrue(settings.enabled)
        assertFalse(settings.seconds)
        assertTrue(settings.amPm)
        assertEquals("Today [TIME]", settings.template)
    }

    @Test
    fun missingOrEmptyTemplateUsesSafeFallback() {
        val empty = ModernCustomTimeSettingPolicy.from(false, false, false, "")
        val absent = ModernCustomTimeSettingPolicy.from(false, false, false, null)
        assertEquals("[TIME]", empty.template)
        assertEquals("[TIME]", absent.template)
    }
}
