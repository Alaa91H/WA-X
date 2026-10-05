package com.wax.module.theme

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThemeEngineTest {
    private lateinit var store: InMemoryKeyValueStore

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
    }

    private fun customTheme(name: String = "Mine") =
        WaTheme.default.copy(
            name = name,
            colors = WaTheme.default.colors.copy(background = 0xFF202020, accent = 0xFF00FF88),
        )

    // --- validation (T130) ------------------------------------------------------------

    @Test
    fun theDefaultThemeIsAlwaysValid() {
        assertTrue(ThemeValidation.validate(WaTheme.default).isEmpty())
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        val badScale = WaTheme.default.copy(typography = WaTheme.default.typography.copy(scale = 3.0f))
        assertTrue(ThemeValidation.validate(badScale).any { it.code == "scale_range" })

        val badRadius = WaTheme.default.copy(shape = WaTheme.default.shape.copy(cornerRadiusDp = 99))
        assertTrue(ThemeValidation.validate(badRadius).any { it.code == "radius_range" })

        val badTransparency = WaTheme.default.copy(transparencyPercent = 150)
        assertTrue(ThemeValidation.validate(badTransparency).any { it.code == "transparency_range" })

        val badColor = WaTheme.default.copy(colors = WaTheme.default.colors.copy(background = -1L))
        assertTrue(ThemeValidation.validate(badColor).any { it.code == "color_range" })
    }

    @Test
    fun unsafeFontNamesAreRejected() {
        val path = WaTheme.default.copy(typography = WaTheme.default.typography.copy(fontFamily = "../../etc/passwd"))
        assertTrue(ThemeValidation.validate(path).any { it.code == "font_unsafe" || it.code == "font_path" })

        val url = WaTheme.default.copy(typography = WaTheme.default.typography.copy(fontFamily = "http://evil.example/font"))
        assertTrue(ThemeValidation.validate(url).any { it.code == "font_unsafe" || it.code == "font_path" })
    }

    @Test
    fun invisibleTextIsRejected() {
        val transparentText =
            WaTheme.default.copy(
                colors = WaTheme.default.colors.copy(textPrimary = 0x00FFFFFF),
            )
        assertTrue(ThemeValidation.validate(transparentText).any { it.code == "text_transparent" })
    }

    @Test
    fun amoledRequiresPureBlack() {
        val inconsistent = WaTheme.default.copy(amoled = true)
        assertTrue(ThemeValidation.validate(inconsistent).any { it.code == "amoled_background" })
        assertTrue(ThemeValidation.validate(inconsistent.withAmoled()).isEmpty())
        assertEquals(WaTheme.AMOLED_BLACK, inconsistent.withAmoled().colors.background)
    }

    // --- repository (T125, T127, T128) ------------------------------------------------

    @Test
    fun theDefaultThemeIsAlwaysAvailableAndCannotBeRemoved() {
        val repository = ThemeRepository(store)
        assertEquals(WaTheme.default.name, repository.globalTheme().name)
        assertFalse(repository.remove(WaTheme.default.name))
    }

    @Test
    fun installingAndSelectingACustomThemePersists() {
        val repository = ThemeRepository(store)
        assertTrue(repository.install(customTheme()).isEmpty())
        assertTrue(repository.setGlobalTheme(customTheme()).isEmpty())
        assertEquals("Mine", ThemeRepository(store).globalTheme().name)
        assertEquals(1, repository.customThemes().size)
    }

    @Test
    fun anInvalidThemeIsNeverInstalled() {
        val repository = ThemeRepository(store)
        val invalid = customTheme().copy(typography = WaTheme.default.typography.copy(scale = 5.0f))
        assertTrue(repository.install(invalid).isNotEmpty())
        assertTrue(repository.customThemes().isEmpty())
        assertTrue(repository.setGlobalTheme(invalid).isNotEmpty())
        assertEquals(WaTheme.default.name, repository.globalTheme().name)
    }

    @Test
    fun aChatUsesItsAssignedThemeAndOtherwiseFallsBackToGlobal() {
        val repository = ThemeRepository(store)
        repository.install(customTheme("Work theme"))
        repository.setGlobalTheme(customTheme("Global theme"))
        assertTrue(repository.assign("chat-1", "Work theme"))
        assertEquals("Work theme", repository.forChat("chat-1").name)
        assertEquals("Global theme", repository.forChat("chat-2").name)
    }

    @Test
    fun removingAnAssignedThemeRevertsThoseChats() {
        val repository = ThemeRepository(store)
        repository.install(customTheme("Temp"))
        repository.assign("chat-1", "Temp")
        assertTrue(repository.remove("Temp"))
        assertEquals(WaTheme.default.name, repository.forChat("chat-1").name)
    }

    @Test
    fun assignmentsCannotPointAtUnknownThemes() {
        val repository = ThemeRepository(store)
        assertFalse(repository.assign("chat-1", "Missing"))
        assertFalse(repository.assign("", WaTheme.default.name))
    }

    // --- accessibility (T133) ---------------------------------------------------------

    @Test
    fun accessibilityDerivationsAreDeterministic() {
        val settings = AccessibilitySettings(largeText = true, largerTouchTargets = true, reducedMotion = true)
        assertEquals(1.3f, settings.fontScaleMultiplier, 0.0001f)
        assertEquals(0.0f, settings.animationScale, 0.0001f)
        assertEquals(56, settings.touchTargetDp(48))
        assertEquals(48, settings.touchTargetDp(10))
        assertEquals(48, AccessibilitySettings().touchTargetDp(10))
        assertTrue(settings.toDisplayLines().contains("Larger text"))
    }

    @Test
    fun highContrastForcesReadableTextForTheThemeBackground() {
        val dark = AccessibilitySettings(highContrast = true).applyToTheme(WaTheme.default)
        assertEquals(0xFFFFFFFFL, dark.colors.textPrimary)

        val lightTheme =
            WaTheme.default.copy(
                colors = WaTheme.default.colors.copy(background = 0xFFF4F4F4),
            )
        val light = AccessibilitySettings(highContrast = true).applyToTheme(lightTheme)
        assertEquals(0xFF000000L, light.colors.textPrimary)
    }

    @Test
    fun largeTextScalesButStaysInsideTheValidatedRange() {
        val scaled = AccessibilitySettings(largeText = true).applyToTheme(WaTheme.default)
        assertEquals(1.3f, scaled.typography.scale, 0.0001f)
        assertTrue(ThemeValidation.validate(scaled).isEmpty())

        val alreadyLarge = WaTheme.default.copy(typography = WaTheme.default.typography.copy(scale = 2.0f))
        val clamped = AccessibilitySettings(largeText = true).applyToTheme(alreadyLarge)
        assertEquals(ThemeValidation.MAX_SCALE, clamped.typography.scale, 0.0001f)
    }

    @Test
    fun accessibilitySettingsPersist() {
        AccessibilityStore(store).save(AccessibilitySettings(highContrast = true, simplifiedLayout = true))
        val reloaded = AccessibilityStore(store).settings()
        assertTrue(reloaded.highContrast)
        assertTrue(reloaded.simplifiedLayout)
        assertFalse(reloaded.largeText)
    }
}
