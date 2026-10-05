package com.wax.module.theme

import com.wax.module.platform.JsonValue
import com.wax.module.platform.MiniJson
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.numberOrNull
import com.wax.module.platform.obj
import com.wax.module.platform.string

/** The manifest of a `.waetheme` package, as described in the roadmap's section 20. */
data class ThemeManifest(
    val schema: Int,
    val name: String,
    val author: String,
    val minimumWaeVersion: String,
)

/** A theme plus its manifest. */
data class ThemePackage(
    val manifest: ThemeManifest,
    val theme: WaTheme,
)

/** The result of decoding one theme document. */
sealed interface ThemeDecodeResult {
    /** The document decoded and validated. */
    data class Decoded(
        val theme: WaTheme,
    ) : ThemeDecodeResult

    /** The document was refused; [problems] says exactly why. */
    data class Rejected(
        val problems: List<ThemeProblem>,
    ) : ThemeDecodeResult
}

/** The result of importing a theme package. */
sealed interface ThemePackageResult {
    /** The package decoded and validated. */
    data class Imported(
        val themePackage: ThemePackage,
    ) : ThemePackageResult

    /** The package was refused. */
    data class Rejected(
        val problems: List<ThemeProblem>,
    ) : ThemePackageResult
}

/**
 * The `.waetheme` codec (T129).
 *
 * The format is a JSON document with a manifest and a theme body, and nothing else. Import
 * only reads known fields, so unknown keys — however they are named — are ignored rather
 * than interpreted: a theme file cannot carry behaviour because there is no code path that
 * treats any of its content as anything but colour, size and name data.
 *
 * Decoding is total: malformed JSON, a missing field, an unsupported schema or a failed
 * range check all produce [ThemePackageResult.Rejected] with reasons, never an exception.
 */
object ThemePackageCodec {
    /** The only schema this version understands. */
    const val SCHEMA_VERSION: Int = 1

    /** The recommended file extension. */
    const val FILE_EXTENSION: String = "waetheme"

    /** Encodes a theme as a package document. */
    fun encode(
        theme: WaTheme,
        author: String,
        minimumWaeVersion: String,
    ): String {
        val document =
            jsonObject(
                "manifest" to
                    jsonObject(
                        "schema" to jsonNumber(SCHEMA_VERSION.toLong()),
                        "name" to jsonString(theme.name),
                        "author" to jsonString(author),
                        "minimumWaeVersion" to jsonString(minimumWaeVersion),
                    ),
                "theme" to encodeTheme(theme),
            )
        return MiniJson.write(document)
    }

    /** Decodes and validates a package document. */
    fun decode(text: String?): ThemePackageResult {
        val root =
            MiniJson.parse(text)?.let { (it as? JsonValue.Obj)?.fields }
                ?: return reject("malformed_json", "The file is not a valid theme package.")

        val manifestFields =
            root.obj("manifest")
                ?: return reject("manifest_missing", "The theme package has no manifest.")
        val schema =
            manifestFields.long("schema")
                ?: return reject("schema_missing", "The manifest does not declare a schema version.")
        if (schema != SCHEMA_VERSION.toLong()) {
            return reject(
                "schema_unsupported",
                "This theme uses schema $schema, but this version supports schema $SCHEMA_VERSION.",
            )
        }
        val author = manifestFields.string("author").orEmpty()
        if (author.length > MAX_AUTHOR_LENGTH || author.any { it.isISOControl() }) {
            return reject("author_unsafe", "The author field contains unsupported characters.")
        }

        val themeFields =
            root.obj("theme")
                ?: return reject("theme_missing", "The theme package has no theme body.")
        val decoded =
            decodeTheme(themeFields)
                ?: return reject("theme_malformed", "The theme body is incomplete or has wrong types.")

        val problems = ThemeValidation.validate(decoded)
        if (problems.isNotEmpty()) return ThemePackageResult.Rejected(problems)

        return ThemePackageResult.Imported(
            ThemePackage(
                manifest =
                    ThemeManifest(
                        schema = schema.toInt(),
                        name = manifestFields.string("name") ?: decoded.name,
                        author = author,
                        minimumWaeVersion = manifestFields.string("minimumWaeVersion").orEmpty(),
                    ),
                theme = decoded,
            ),
        )
    }

    /** Encodes a theme without a package wrapper, for local storage. */
    fun encodeTheme(theme: WaTheme): JsonValue.Obj =
        jsonObject(
            "name" to jsonString(theme.name),
            "transparency" to jsonNumber(theme.transparencyPercent.toLong()),
            "amoled" to jsonBoolean(theme.amoled),
            "materialYou" to jsonBoolean(theme.materialYou),
            "colors" to
                jsonObject(
                    "background" to jsonNumber(theme.colors.background),
                    "surface" to jsonNumber(theme.colors.surface),
                    "primary" to jsonNumber(theme.colors.primary),
                    "onPrimary" to jsonNumber(theme.colors.onPrimary),
                    "outgoingBubble" to jsonNumber(theme.colors.outgoingBubble),
                    "incomingBubble" to jsonNumber(theme.colors.incomingBubble),
                    "textPrimary" to jsonNumber(theme.colors.textPrimary),
                    "textSecondary" to jsonNumber(theme.colors.textSecondary),
                    "accent" to jsonNumber(theme.colors.accent),
                ),
            "typography" to
                jsonObject(
                    "fontFamily" to jsonString(theme.typography.fontFamily),
                    "scale" to jsonNumber(theme.typography.scale.toDouble()),
                    "weight" to jsonNumber(theme.typography.weight.toLong()),
                    "lineSpacing" to jsonNumber(theme.typography.lineSpacing.toDouble()),
                    "messageScale" to jsonNumber(theme.typography.messageScale.toDouble()),
                ),
            "shape" to
                jsonObject(
                    "cornerRadius" to jsonNumber(theme.shape.cornerRadiusDp.toLong()),
                    "spacing" to jsonNumber(theme.shape.spacingDp.toLong()),
                    "bubbleStyle" to jsonString(theme.shape.bubbleStyle.name),
                ),
        )

    /** Encodes a list of themes for storage. */
    fun encodeList(themes: List<WaTheme>): String = MiniJson.write(jsonArray(themes.map { encodeTheme(it) }))

    /** Decodes a stored theme list, dropping malformed entries rather than failing the list. */
    fun decodeList(text: String?): List<WaTheme> {
        val items = MiniJson.parse(text)?.let { (it as? JsonValue.Arr)?.items } ?: return emptyList()
        return items.mapNotNull { item ->
            val fields = (item as? JsonValue.Obj)?.fields ?: return@mapNotNull null
            decodeTheme(fields)
        }
    }

    /** Decodes a theme body; null when a required field is missing or mistyped. */
    private fun decodeTheme(fields: Map<String, JsonValue>): WaTheme? {
        val name = fields.string("name") ?: return null
        val colors = fields.obj("colors") ?: return null
        val typography = fields.obj("typography") ?: return null
        val shape = fields.obj("shape") ?: return null

        fun color(key: String): Long? =
            colors[key]?.numberOrNull()?.let {
                if (it % 1.0 != 0.0 || it < 0.0 || it > 0xFFFFFFFF.toDouble()) null else it.toLong()
            }

        val decodedColors =
            ThemeColors(
                background = color("background") ?: return null,
                surface = color("surface") ?: return null,
                primary = color("primary") ?: return null,
                onPrimary = color("onPrimary") ?: return null,
                outgoingBubble = color("outgoingBubble") ?: return null,
                incomingBubble = color("incomingBubble") ?: return null,
                textPrimary = color("textPrimary") ?: return null,
                textSecondary = color("textSecondary") ?: return null,
                accent = color("accent") ?: return null,
            )
        val decodedTypography =
            ThemeTypography(
                fontFamily = typography.string("fontFamily") ?: return null,
                scale = typography["scale"]?.numberOrNull()?.toFloat() ?: return null,
                weight = (typography.long("weight") ?: return null).toInt(),
                lineSpacing = typography["lineSpacing"]?.numberOrNull()?.toFloat() ?: return null,
                messageScale = typography["messageScale"]?.numberOrNull()?.toFloat() ?: 1.0f,
            )
        val decodedShape =
            ThemeShape(
                cornerRadiusDp = (shape.long("cornerRadius") ?: return null).toInt(),
                spacingDp = (shape.long("spacing") ?: return null).toInt(),
                bubbleStyle =
                    BubbleStyle.entries.firstOrNull { it.name == shape.string("bubbleStyle") }
                        ?: return null,
            )
        return WaTheme(
            name = name,
            colors = decodedColors,
            typography = decodedTypography,
            shape = decodedShape,
            transparencyPercent = (fields.long("transparency") ?: 0L).toInt(),
            amoled = fields.boolean("amoled") ?: false,
            materialYou = fields.boolean("materialYou") ?: false,
        )
    }

    private fun reject(
        code: String,
        message: String,
    ): ThemePackageResult = ThemePackageResult.Rejected(listOf(ThemeProblem(code, message)))

    /** The longest accepted author string. */
    const val MAX_AUTHOR_LENGTH: Int = 64
}

/** One gallery entry: a theme the user can look at and install. */
data class GalleryEntry(
    val theme: WaTheme,
    val builtIn: Boolean,
    val installed: Boolean,
) {
    /** One line for the gallery grid. */
    fun toDisplayLine(): String = theme.toDisplayLine() + if (installed) " (installed)" else ""
}

/**
 * The local theme gallery (T131).
 *
 * It starts local, and the only themes in it are data the module already trusts: the
 * built-in samples and themes the user installed. A future remote gallery can serve the
 * same `.waetheme` documents through [ThemePackageCodec], which is precisely why the codec
 * rejects anything that is not data — remote distribution must never become a code path.
 */
class ThemeGallery(
    private val repository: ThemeRepository,
) {
    /** Every entry: built-in samples first, then custom themes. */
    fun entries(): List<GalleryEntry> {
        val custom = repository.customThemes()
        val samples =
            sampleThemes().map { sample ->
                GalleryEntry(sample, builtIn = true, installed = custom.any { it.name == sample.name })
            }
        val user =
            custom.map { theme ->
                GalleryEntry(theme, builtIn = false, installed = true)
            }
        return samples + user
    }

    /** Installs a gallery theme after validation. */
    fun install(theme: WaTheme): List<ThemeProblem> = repository.install(theme)

    /** The policy statement shown in the gallery. */
    fun distributionPolicy(): String = "Themes are data only: colours, sizes and names. No theme can run code."

    private fun sampleThemes(): List<WaTheme> =
        listOf(
            WaTheme(
                name = "Midnight",
                colors =
                    ThemeColors(
                        background = 0xFF0B0F14,
                        surface = 0xFF121820,
                        primary = 0xFF7AA2F7,
                        onPrimary = 0xFF071018,
                        outgoingBubble = 0xFF1B3A5B,
                        incomingBubble = 0xFF161B22,
                        textPrimary = 0xFFE6EDF3,
                        textSecondary = 0xFF8B949E,
                        accent = 0xFF7AA2F7,
                    ),
                typography = ThemeTypography("default", 1.0f, 400, 1.0f),
                shape = ThemeShape(14, 8, BubbleStyle.ROUNDED),
            ),
            WaTheme(
                name = "Daylight",
                colors =
                    ThemeColors(
                        background = 0xFFF7F9FB,
                        surface = 0xFFFFFFFF,
                        primary = 0xFF1E88E5,
                        onPrimary = 0xFFFFFFFF,
                        outgoingBubble = 0xFFD7EBFF,
                        incomingBubble = 0xFFEDEFF2,
                        textPrimary = 0xFF101418,
                        textSecondary = 0xFF5F6B76,
                        accent = 0xFF1E88E5,
                    ),
                typography = ThemeTypography("default", 1.0f, 400, 1.0f),
                shape = ThemeShape(10, 8, BubbleStyle.ROUNDED),
            ),
            WaTheme(
                name = "Paper",
                colors =
                    ThemeColors(
                        background = 0xFFF3EFE6,
                        surface = 0xFFFBF8F1,
                        primary = 0xFF8A6B3F,
                        onPrimary = 0xFFFFFFFF,
                        outgoingBubble = 0xFFE4D9C3,
                        incomingBubble = 0xFFF1EBDE,
                        textPrimary = 0xFF2E2A22,
                        textSecondary = 0xFF6B6355,
                        accent = 0xFF8A6B3F,
                    ),
                typography = ThemeTypography("default", 1.0f, 400, 1.15f),
                shape = ThemeShape(6, 10, BubbleStyle.MINIMAL),
            ),
            WaTheme(
                name = "Pure Black",
                colors =
                    ThemeColors(
                        background = WaTheme.AMOLED_BLACK,
                        surface = WaTheme.AMOLED_BLACK,
                        primary = 0xFF00E0A4,
                        onPrimary = 0xFF00291D,
                        outgoingBubble = 0xFF123B33,
                        incomingBubble = 0xFF111111,
                        textPrimary = 0xFFEDEDED,
                        textSecondary = 0xFF909090,
                        accent = 0xFF00E0A4,
                    ),
                typography = ThemeTypography("default", 1.0f, 400, 1.0f),
                shape = ThemeShape(10, 8, BubbleStyle.ROUNDED),
                amoled = true,
            ),
        )
}
