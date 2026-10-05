package com.wmods.wppenhacer.theme

import com.wmods.wppenhacer.platform.KeyValueStore

/** Around which bubble shape the message surface is drawn. */
enum class BubbleStyle {
    ROUNDED,
    SQUARE,
    TAILED,
    MINIMAL,
}

/** The colour slots a theme controls. Values are 0xAARRGGBB, held as unsigned longs. */
data class ThemeColors(
    val background: Long,
    val surface: Long,
    val primary: Long,
    val onPrimary: Long,
    val outgoingBubble: Long,
    val incomingBubble: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val accent: Long,
) {
    /** All slots as a list, for validation and diffing. */
    fun slots(): List<Pair<String, Long>> =
        listOf(
            "background" to background,
            "surface" to surface,
            "primary" to primary,
            "onPrimary" to onPrimary,
            "outgoingBubble" to outgoingBubble,
            "incomingBubble" to incomingBubble,
            "textPrimary" to textPrimary,
            "textSecondary" to textSecondary,
            "accent" to accent,
        )
}

/** Typography controls (T132). */
data class ThemeTypography(
    /** Font family name; must be a plain name, never a path or URL. */
    val fontFamily: String,
    /** Size multiplier, 0.5..2.0. */
    val scale: Float,
    /** Font weight, 100..900. */
    val weight: Int,
    /** Line spacing multiplier, 0.8..2.0. */
    val lineSpacing: Float,
    /** Extra scale applied to message text only, 0.5..2.0. */
    val messageScale: Float = 1.0f,
)

/** Shape and density controls. */
data class ThemeShape(
    /** Corner radius in dp, 0..48. */
    val cornerRadiusDp: Int,
    /** Base spacing in dp, 0..32. */
    val spacingDp: Int,
    val bubbleStyle: BubbleStyle,
)

/**
 * A complete theme.
 *
 * The model is deliberately data-only: no resource references, no asset paths, no code.
 * That is what makes a theme safe to import from anywhere (T130) and what makes the
 * `.waetheme` export a serialisation rather than a plugin format.
 */
data class WaTheme(
    val name: String,
    val colors: ThemeColors,
    val typography: ThemeTypography,
    val shape: ThemeShape,
    /** Window transparency percentage, 0..100. */
    val transparencyPercent: Int = 0,
    /** True-black surfaces for AMOLED panels (T127). */
    val amoled: Boolean = false,
    /** Whether the theme defers to system dynamic colours (T126). */
    val materialYou: Boolean = false,
) {
    /** A copy forced to true black when [amoled] is set. */
    fun withAmoled(): WaTheme = if (!amoled) this else copy(colors = colors.copy(background = AMOLED_BLACK, surface = AMOLED_BLACK))

    /** One line for the theme list. */
    fun toDisplayLine(): String = "$name (${if (amoled) "AMOLED" else "standard"}${if (materialYou) ", dynamic" else ""})"

    companion object {
        /** Pure black used by AMOLED mode. */
        const val AMOLED_BLACK: Long = 0xFF000000L

        /** The theme used before the user chooses one. */
        val default: WaTheme =
            WaTheme(
                name = "WaEnhancer",
                colors =
                    ThemeColors(
                        background = 0xFF111417,
                        surface = 0xFF1B1F23,
                        primary = 0xFF00A884,
                        onPrimary = 0xFF07281F,
                        outgoingBubble = 0xFF005C4B,
                        incomingBubble = 0xFF1F2C34,
                        textPrimary = 0xFFE9EDEF,
                        textSecondary = 0xFF8696A0,
                        accent = 0xFF00A884,
                    ),
                typography =
                    ThemeTypography(
                        fontFamily = "default",
                        scale = 1.0f,
                        weight = 400,
                        lineSpacing = 1.0f,
                    ),
                shape = ThemeShape(cornerRadiusDp = 12, spacingDp = 8, bubbleStyle = BubbleStyle.ROUNDED),
            )
    }
}

/** One validation problem, with a code and an explanation. */
data class ThemeProblem(
    val code: String,
    val message: String,
) {
    /** One line for import failures. */
    fun toDisplayLine(): String = "$code: $message"
}

/**
 * Validates a theme before it is applied (T130).
 *
 * Four kinds of rejection, matching the requirement: malformed structure (missing values),
 * unsupported schema (handled by the codec), out-of-range values, and unsafe payloads. The
 * unsafe-payload check is deliberately blunt — font names cannot contain paths, URLs or
 * control characters — because the theme engine's promise is that a theme file is data, and
 * a string that looks like a path is how that promise gets broken.
 */
object ThemeValidation {
    private val SAFE_FONT = Regex("^[A-Za-z0-9 _-]{1,64}$")
    private val UNSAFE_MARKERS = listOf("://", "file:", "content:", "url(", "../", "..\\")

    /** Problems with [theme], empty when it is usable. */
    fun validate(theme: WaTheme): List<ThemeProblem> {
        val problems = ArrayList<ThemeProblem>()

        if (theme.name.isBlank()) problems.add(ThemeProblem("name_blank", "The theme needs a name."))
        if (theme.name.length > MAX_NAME_LENGTH) {
            problems.add(ThemeProblem("name_too_long", "The theme name must be at most $MAX_NAME_LENGTH characters."))
        }
        theme.colors.slots().forEach { (slot, value) ->
            if (value !in 0..0xFFFFFFFFL) {
                problems.add(ThemeProblem("color_range", "Colour $slot is outside the 0xAARRGGBB range."))
            }
        }
        if (theme.typography.scale !in MIN_SCALE..MAX_SCALE) {
            problems.add(ThemeProblem("scale_range", "The text scale must be between $MIN_SCALE and $MAX_SCALE."))
        }
        if (theme.typography.messageScale !in MIN_SCALE..MAX_SCALE) {
            problems.add(
                ThemeProblem("message_scale_range", "The message text scale must be between $MIN_SCALE and $MAX_SCALE."),
            )
        }
        if (theme.typography.weight !in 100..900) {
            problems.add(ThemeProblem("weight_range", "The font weight must be between 100 and 900."))
        }
        if (theme.typography.lineSpacing !in MIN_LINE_SPACING..MAX_LINE_SPACING) {
            problems.add(
                ThemeProblem(
                    "line_spacing_range",
                    "Line spacing must be between $MIN_LINE_SPACING and $MAX_LINE_SPACING.",
                ),
            )
        }
        if (!SAFE_FONT.matches(theme.typography.fontFamily)) {
            problems.add(
                ThemeProblem(
                    "font_unsafe",
                    "The font family must be a plain name of letters, digits, spaces, dashes or underscores.",
                ),
            )
        }
        if (UNSAFE_MARKERS.any { theme.typography.fontFamily.contains(it, ignoreCase = true) }) {
            problems.add(ThemeProblem("font_path", "The font family must not contain paths or URLs."))
        }
        if (theme.shape.cornerRadiusDp !in 0..48) {
            problems.add(ThemeProblem("radius_range", "The corner radius must be between 0 and 48 dp."))
        }
        if (theme.shape.spacingDp !in 0..32) {
            problems.add(ThemeProblem("spacing_range", "The spacing must be between 0 and 32 dp."))
        }
        if (theme.transparencyPercent !in 0..100) {
            problems.add(ThemeProblem("transparency_range", "Transparency must be between 0 and 100."))
        }
        if (theme.amoled && theme.colors.background != WaTheme.AMOLED_BLACK) {
            problems.add(
                ThemeProblem(
                    "amoled_background",
                    "AMOLED mode requires a pure black background; use withAmoled() to normalise it.",
                ),
            )
        }
        theme.colors
            .slots()
            .filter { (slot, _) -> slot.startsWith("text") }
            .forEach { (slot, value) ->
                // Fully transparent text is almost always a mistake and renders as invisible.
                if (value ushr 24 == 0L) {
                    problems.add(ThemeProblem("text_transparent", "Text colour $slot is fully transparent."))
                }
            }
        return problems
    }

    /** Renders [problems] for a dialog. */
    fun describe(problems: List<ThemeProblem>): String = problems.joinToString("\n") { it.toDisplayLine() }

    /** The longest accepted theme name. */
    const val MAX_NAME_LENGTH: Int = 48

    /** The smallest accepted text scale. */
    const val MIN_SCALE: Float = 0.5f

    /** The largest accepted text scale. */
    const val MAX_SCALE: Float = 2.0f

    /** The smallest accepted line spacing. */
    const val MIN_LINE_SPACING: Float = 0.8f

    /** The largest accepted line spacing. */
    const val MAX_LINE_SPACING: Float = 2.0f
}

/**
 * Owns the local theme set, the global selection and per-chat assignments.
 *
 * The built-in default is always available and cannot be removed, so a chat can always fall
 * back to something. Per-chat themes (T128) are stored as assignments; resolving a chat's
 * theme is one lookup with a global fallback, so a theme change propagates to every chat
 * that did not explicitly override it — which is the behaviour "global fallback" promises.
 */
class ThemeRepository(
    private val store: KeyValueStore,
) {
    /** Custom themes, in installation order. */
    fun customThemes(): List<WaTheme> = ThemePackageCodec.decodeList(store.getString(KEY_THEMES))

    /** The default plus custom themes. */
    fun themes(): List<WaTheme> = listOf(WaTheme.default) + customThemes()

    /** The globally selected theme. */
    fun globalTheme(): WaTheme {
        val name = store.getString(KEY_GLOBAL) ?: return WaTheme.default
        return themes().firstOrNull { it.name == name } ?: WaTheme.default
    }

    /**
     * Sets the global theme.
     *
     * The theme must validate; an invalid theme is never made global, so the reporting UI
     * cannot accidentally bypass the same rules the importer enforces.
     */
    fun setGlobalTheme(theme: WaTheme): List<ThemeProblem> {
        val problems = ThemeValidation.validate(theme)
        if (problems.isNotEmpty()) return problems
        store.putString(KEY_GLOBAL, theme.name)
        if (customThemes().none { it.name == theme.name } && theme.name != WaTheme.default.name) {
            writeThemes(customThemes() + theme)
        }
        return emptyList()
    }

    /** Installs (or replaces) a custom theme, returning validation problems when refused. */
    fun install(theme: WaTheme): List<ThemeProblem> {
        val problems = ThemeValidation.validate(theme)
        if (problems.isNotEmpty()) return problems
        writeThemes(customThemes().filterNot { it.name == theme.name } + theme)
        return emptyList()
    }

    /** Removes a custom theme. The built-in default cannot be removed. */
    fun remove(name: String): Boolean {
        if (name == WaTheme.default.name) return false
        val current = customThemes()
        val remaining = current.filterNot { it.name == name }
        if (remaining.size == current.size) return false
        writeThemes(remaining)
        if (store.getString(KEY_GLOBAL) == name) store.remove(KEY_GLOBAL)
        // Chats assigned to the removed theme fall back to the global theme.
        assignments().filterValues { it == name }.keys.forEach { store.remove(assignmentKey(it)) }
        return true
    }

    /** Assigns a theme to one chat (T128). */
    fun assign(
        chatId: String,
        themeName: String,
    ): Boolean {
        if (chatId.isBlank()) return false
        if (themes().none { it.name == themeName }) return false
        store.putString(assignmentKey(chatId), themeName)
        return true
    }

    /** Removes a chat's assignment so it follows the global theme again. */
    fun clearAssignment(chatId: String): Boolean {
        if (store.getString(assignmentKey(chatId)) == null) return false
        store.remove(assignmentKey(chatId))
        return true
    }

    /** The theme that applies to [chatId], with the global fallback. */
    fun forChat(chatId: String): WaTheme {
        val assigned = store.getString(assignmentKey(chatId)) ?: return globalTheme()
        return themes().firstOrNull { it.name == assigned } ?: globalTheme()
    }

    /** Every chat with an explicit assignment. */
    fun assignments(): Map<String, String> {
        val prefix = KEY_ASSIGNMENT
        return store.keys(prefix).associate { key ->
            key.removePrefix(prefix) to store.getString(key).orEmpty()
        }
    }

    /** Drops custom themes, the global choice and every assignment. */
    fun clear() {
        store.remove(KEY_THEMES)
        store.remove(KEY_GLOBAL)
        store.keys(KEY_ASSIGNMENT).forEach { store.remove(it) }
    }

    private fun writeThemes(themes: List<WaTheme>) {
        store.putString(KEY_THEMES, ThemePackageCodec.encodeList(themes))
    }

    private fun assignmentKey(chatId: String): String = "$KEY_ASSIGNMENT$chatId"

    companion object {
        /** Storage key for the custom theme array. */
        const val KEY_THEMES: String = "wae.theme.themes"

        /** Storage key for the global theme name. */
        const val KEY_GLOBAL: String = "wae.theme.global"

        /** Storage key prefix for per-chat assignments. */
        const val KEY_ASSIGNMENT: String = "wae.theme.assignment."
    }
}
