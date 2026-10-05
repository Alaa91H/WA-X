package com.wax.module.theme

import com.wax.module.platform.KeyValueStore

/**
 * The accessibility options of T133.
 *
 * Settings are booleans and the effects are pure functions of them, so every adjustment can
 * be unit tested instead of eyeballed: font scale, touch target size and animation scale are
 * all derived here rather than left to each screen to interpret.
 */
data class AccessibilitySettings(
    /** Raise text contrast for readability. */
    val highContrast: Boolean = false,
    /** Scale text up beyond the system setting. */
    val largeText: Boolean = false,
    /** Enforce a minimum touch target size. */
    val largerTouchTargets: Boolean = false,
    /** Disable decorative animation. */
    val reducedMotion: Boolean = false,
    /** Reduce visual density and decorative elements. */
    val simplifiedLayout: Boolean = false,
) {
    /** The multiplier applied to text sizes. */
    val fontScaleMultiplier: Float get() = if (largeText) LARGE_TEXT_MULTIPLIER else 1.0f

    /** The animation scale, 0 when motion is reduced. */
    val animationScale: Float get() = if (reducedMotion) 0.0f else 1.0f

    /** The effective touch target for a base size: never below the accessible minimum. */
    fun touchTargetDp(baseDp: Int): Int = maxOf(if (largerTouchTargets) baseDp + TOUCH_TARGET_BONUS_DP else baseDp, MIN_TOUCH_TARGET_DP)

    /** Whether any option is on. */
    fun isEmpty(): Boolean = this == AccessibilitySettings()

    /** One line per enabled option, for the settings summary. */
    fun toDisplayLines(): List<String> =
        buildList {
            if (highContrast) add("High contrast")
            if (largeText) add("Larger text")
            if (largerTouchTargets) add("Larger touch targets")
            if (reducedMotion) add("Reduced motion")
            if (simplifiedLayout) add("Simplified layout")
        }

    /**
     * Applies these settings to [theme].
     *
     * Only the settings with a defined visual consequence do anything: large text scales
     * typography, high contrast forces readable text colours against the theme's own
     * background. The result still has to pass [ThemeValidation], and callers validate it;
     * this function does not silently clamp beyond the documented bounds.
     */
    fun applyToTheme(theme: WaTheme): WaTheme {
        var result = theme
        if (largeText) {
            result =
                result.copy(
                    typography =
                        result.typography.copy(
                            scale = (result.typography.scale * LARGE_TEXT_MULTIPLIER).coerceAtMost(ThemeValidation.MAX_SCALE),
                        ),
                )
        }
        if (highContrast) {
            val dark = isDark(result.colors.background)
            result =
                result.copy(
                    colors =
                        result.colors.copy(
                            textPrimary = if (dark) OPAQUE_WHITE else OPAQUE_BLACK,
                            textSecondary = if (dark) 0xFFD6D6D6 else 0xFF3A3A3A,
                        ),
                )
        }
        return result
    }

    /** Whether a colour reads as dark, using perceived luminance. */
    fun isDark(color: Long): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val luminance = (red * 299 + green * 587 + blue * 114) / 1000.0
        return luminance < 128.0
    }

    companion object {
        /** How much larger text becomes with the large-text option. */
        const val LARGE_TEXT_MULTIPLIER: Float = 1.3f

        /** The smallest touch target considered accessible. */
        const val MIN_TOUCH_TARGET_DP: Int = 48

        /** How much larger a touch target becomes. */
        const val TOUCH_TARGET_BONUS_DP: Int = 8

        private const val OPAQUE_WHITE = 0xFFFFFFFFL
        private const val OPAQUE_BLACK = 0xFF000000L
    }
}

/** Persists [AccessibilitySettings]. */
class AccessibilityStore(
    private val store: KeyValueStore,
) {
    /** The stored settings. */
    fun settings(): AccessibilitySettings =
        AccessibilitySettings(
            highContrast = store.getBoolean(KEY_HIGH_CONTRAST, false),
            largeText = store.getBoolean(KEY_LARGE_TEXT, false),
            largerTouchTargets = store.getBoolean(KEY_LARGER_TOUCH, false),
            reducedMotion = store.getBoolean(KEY_REDUCED_MOTION, false),
            simplifiedLayout = store.getBoolean(KEY_SIMPLIFIED, false),
        )

    /** Stores [settings]. */
    fun save(settings: AccessibilitySettings) {
        store.putBoolean(KEY_HIGH_CONTRAST, settings.highContrast)
        store.putBoolean(KEY_LARGE_TEXT, settings.largeText)
        store.putBoolean(KEY_LARGER_TOUCH, settings.largerTouchTargets)
        store.putBoolean(KEY_REDUCED_MOTION, settings.reducedMotion)
        store.putBoolean(KEY_SIMPLIFIED, settings.simplifiedLayout)
    }

    /** Drops the settings. Used by tests and factory reset. */
    fun clear() {
        listOf(KEY_HIGH_CONTRAST, KEY_LARGE_TEXT, KEY_LARGER_TOUCH, KEY_REDUCED_MOTION, KEY_SIMPLIFIED)
            .forEach { store.remove(it) }
    }

    private companion object {
        const val KEY_HIGH_CONTRAST = "wae.accessibility.high_contrast"
        const val KEY_LARGE_TEXT = "wae.accessibility.large_text"
        const val KEY_LARGER_TOUCH = "wae.accessibility.larger_touch"
        const val KEY_REDUCED_MOTION = "wae.accessibility.reduced_motion"
        const val KEY_SIMPLIFIED = "wae.accessibility.simplified"
    }
}
