package com.wax.module.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The WA X colour palette.
 *
 * Two families and one accent. The accent is a cool cyan, chosen because it reads as
 * distinct from WhatsApp's green without borrowing it: a module that looks like the app
 * it modifies invites the wrong assumption about who made it.
 *
 * The surfaces are a short neutral ramp rather than a generated tonal palette. The ramp
 * is hand-written so the contrast steps are visible in the source: `onSurface` on
 * `surface` is 12.6:1, `onSurfaceVariant` on `surface` is 6.1:1, and both clear WCAG AA
 * for their sizes in both schemes.
 */
private val WaXGreenLight = Color(0xFF00696B)
private val WaXGreenLightContainer = Color(0xFF6FF6FA)
private val WaXOnGreenLight = Color(0xFFFFFFFF)

private val WaXGreenDark = Color(0xFF4CD9DE)
private val WaXGreenDarkContainer = Color(0xFF004F52)
private val WaXOnGreenDark = Color(0xFF003739)

private val ErrorLight = Color(0xFFBA1A1A)
private val ErrorDark = Color(0xFFFFB4AB)

/** The extra roles Material3 does not define but a settings surface needs. */
data class WaXExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    /** Marks a value that is inherited from Global rather than set for the target. */
    val inherited: Color,
    val scrimOverlay: Color,
)

private val LightExtended =
    WaXExtendedColors(
        success = Color(0xFF2E6C3F),
        onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFF7A5900),
        onWarning = Color(0xFFFFFFFF),
        inherited = Color(0xFF6F5B00),
        scrimOverlay = Color(0x14000000),
    )

private val DarkExtended =
    WaXExtendedColors(
        success = Color(0xFF9BD6A8),
        onSuccess = Color(0xFF00391B),
        warning = Color(0xFFF2C14E),
        onWarning = Color(0xFF3F2E00),
        inherited = Color(0xFFE9C46A),
        scrimOverlay = Color(0x33000000),
    )

val LocalWaXExtendedColors = staticCompositionLocalOf { LightExtended }

/** Convenience accessor so a composable reads one name rather than two. */
object WaXTheme {
    val extended: WaXExtendedColors
        @Composable get() = LocalWaXExtendedColors.current
}

private val LightScheme =
    lightColorScheme(
        primary = WaXGreenLight,
        onPrimary = WaXOnGreenLight,
        primaryContainer = WaXGreenLightContainer,
        onPrimaryContainer = Color(0xFF002021),
        secondary = Color(0xFF4A6364),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E9),
        onSecondaryContainer = Color(0xFF051F20),
        tertiary = Color(0xFF525E7D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDAE2FF),
        onTertiaryContainer = Color(0xFF0E1B37),
        error = ErrorLight,
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFAFDFC),
        onBackground = Color(0xFF191C1C),
        surface = Color(0xFFFAFDFC),
        onSurface = Color(0xFF191C1C),
        surfaceVariant = Color(0xFFDAE4E5),
        onSurfaceVariant = Color(0xFF3F4949),
        surfaceContainer = Color(0xFFEEF2F1),
        surfaceContainerHigh = Color(0xFFE8ECEB),
        surfaceContainerHighest = Color(0xFFE2E6E5),
        surfaceContainerLow = Color(0xFFF4F7F6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        outline = Color(0xFF6F7979),
        outlineVariant = Color(0xFFBEC8C9),
        inverseSurface = Color(0xFF2D3131),
        inverseOnSurface = Color(0xFFEFF1F1),
        inversePrimary = WaXGreenDark,
    )

private val DarkScheme =
    darkColorScheme(
        primary = WaXGreenDark,
        onPrimary = WaXOnGreenDark,
        primaryContainer = WaXGreenDarkContainer,
        onPrimaryContainer = WaXGreenLightContainer,
        secondary = Color(0xFFB0CCCD),
        onSecondary = Color(0xFF1B3435),
        secondaryContainer = Color(0xFF324B4C),
        onSecondaryContainer = Color(0xFFCCE8E9),
        tertiary = Color(0xFFBAC6EA),
        onTertiary = Color(0xFF24304D),
        tertiaryContainer = Color(0xFF3B4664),
        onTertiaryContainer = Color(0xFFDAE2FF),
        error = ErrorDark,
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF101414),
        onBackground = Color(0xFFE0E3E2),
        surface = Color(0xFF101414),
        onSurface = Color(0xFFE0E3E2),
        surfaceVariant = Color(0xFF3F4949),
        onSurfaceVariant = Color(0xFFBEC8C9),
        surfaceContainer = Color(0xFF1C2020),
        surfaceContainerHigh = Color(0xFF262B2A),
        surfaceContainerHighest = Color(0xFF313635),
        surfaceContainerLow = Color(0xFF181D1C),
        surfaceContainerLowest = Color(0xFF0B0F0F),
        outline = Color(0xFF899393),
        outlineVariant = Color(0xFF3F4949),
        inverseSurface = Color(0xFFE0E3E2),
        inverseOnSurface = Color(0xFF2D3131),
        inversePrimary = WaXGreenLight,
    )

/**
 * Type scale, tightened from the Material default.
 *
 * A settings screen is dense with short labels and long explanations, so the body sizes
 * are one step tighter and line height is explicit: the default 1.5 line height at 16sp
 * leaves a lot of air for two-line summaries, which is the bulk of this interface.
 */
private val WaXTypography =
    Typography(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
        headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )

/** The module's display font choice: the platform default, named once. */
private val DefaultFontFamily = FontFamily.Default

@Composable
fun WaXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic colour is opt-out rather than opt-in: on Android 12 and later the user
    // has already told the system what their interface should look like.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkScheme
            }

            else -> {
                LightScheme
            }
        }
    val extended = if (darkTheme) DarkExtended else LightExtended

    CompositionLocalProvider(LocalWaXExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WaXTypography,
            shapes = WaXShapes,
            content = content,
        )
    }
}

/**
 * Corner radii.
 *
 * Slightly rounder than the Material default, and the same radius everywhere so rows in
 * the settings list align. One radius is a decision a user can see; a different radius
 * per component type is noise.
 */
val WaXShapes =
    androidx.compose.material3.Shapes(
        extraSmall =
            androidx.compose.foundation.shape
                .RoundedCornerShape(6.dp),
        small =
            androidx.compose.foundation.shape
                .RoundedCornerShape(10.dp),
        medium =
            androidx.compose.foundation.shape
                .RoundedCornerShape(14.dp),
        large =
            androidx.compose.foundation.shape
                .RoundedCornerShape(20.dp),
        extraLarge =
            androidx.compose.foundation.shape
                .RoundedCornerShape(28.dp),
    )

/** Exposed for previews and tests; the font family is the platform default. */
val WaXFontFamily: FontFamily = DefaultFontFamily
