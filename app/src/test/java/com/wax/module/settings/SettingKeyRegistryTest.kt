package com.wax.module.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingKeyRegistryTest {
    @Test
    fun `color preferences keep their persisted integer type`() {
        listOf(
            "primary_color",
            "background_color",
            "text_color",
            "bubble_left",
            "bubble_right",
        ).forEach { key ->
            assertEquals(SettingKeyRegistry.Kind.INT, SettingKeyRegistry.find(key)?.kind)
        }
    }

    @Test
    fun `action rows disabled placeholders and credentials are not target settings`() {
        listOf(
            "per_target_settings",
            "call_recording_settings",
            "video_call_screen_rec",
            "assemblyai_key",
        ).forEach { key ->
            assertNull(SettingKeyRegistry.find(key))
        }
    }
}
