// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.caochaoxing.windows.state.AppThemeMode

/**
 * Warm neutral surfaces, charcoal ink, and clay accent shared across all Caochaoxing clients.
 */
object CxPaletteSpecs {
    val canvasLight = Color(0xFFF8F7F3)
    val canvasDark = Color(0xFF1F1E1C)

    val surfaceLight = Color(0xFFFFFEFA)
    val surfaceDark = Color(0xFF292825)

    val surfaceSecondaryLight = Color(0xFFEFEDE7)
    val surfaceSecondaryDark = Color(0xFF35332E)

    val borderLight = Color(0xFFDCD8CE)
    val borderDark = Color(0xFF4D4A44)

    val inkLight = Color(0xFF272520)
    val inkDark = Color(0xFFF2EFE8)

    val inkSecondaryLight = Color(0xFF625F57)
    val inkSecondaryDark = Color(0xFFC9C4B8)

    val inkTertiaryLight = Color(0xFF747066)
    val inkTertiaryDark = Color(0xFFA7A297)

    val accentLight = Color(0xFFA34B35)
    val accentDark = Color(0xFFEEA38B)

    val onAccentLight = Color(0xFFFFFFFF)
    val onAccentDark = Color(0xFF2B1C17)

    val sageLight = Color(0xFF3F6A56)
    val sageDark = Color(0xFF9FCAAE)

    val amberLight = Color(0xFF845D28)
    val amberDark = Color(0xFFDBB77C)

    val cinnabarLight = Color(0xFFA43A39)
    val cinnabarDark = Color(0xFFF19E98)

    val plumLight = Color(0xFF735773)
    val plumDark = Color(0xFFC8ABCA)
}

@Immutable
data class CxColors(
    val canvas: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val border: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val sage: Color,
    val amber: Color,
    val cinnabar: Color,
    val plum: Color,
    val isDark: Boolean,
)

val LightCxColors = CxColors(
    canvas = CxPaletteSpecs.canvasLight,
    surface = CxPaletteSpecs.surfaceLight,
    surfaceSecondary = CxPaletteSpecs.surfaceSecondaryLight,
    border = CxPaletteSpecs.borderLight,
    ink = CxPaletteSpecs.inkLight,
    inkSecondary = CxPaletteSpecs.inkSecondaryLight,
    inkTertiary = CxPaletteSpecs.inkTertiaryLight,
    accent = CxPaletteSpecs.accentLight,
    onAccent = CxPaletteSpecs.onAccentLight,
    sage = CxPaletteSpecs.sageLight,
    amber = CxPaletteSpecs.amberLight,
    cinnabar = CxPaletteSpecs.cinnabarLight,
    plum = CxPaletteSpecs.plumLight,
    isDark = false,
)

val DarkCxColors = CxColors(
    canvas = CxPaletteSpecs.canvasDark,
    surface = CxPaletteSpecs.surfaceDark,
    surfaceSecondary = CxPaletteSpecs.surfaceSecondaryDark,
    border = CxPaletteSpecs.borderDark,
    ink = CxPaletteSpecs.inkDark,
    inkSecondary = CxPaletteSpecs.inkSecondaryDark,
    inkTertiary = CxPaletteSpecs.inkTertiaryDark,
    accent = CxPaletteSpecs.accentDark,
    onAccent = CxPaletteSpecs.onAccentDark,
    sage = CxPaletteSpecs.sageDark,
    amber = CxPaletteSpecs.amberDark,
    cinnabar = CxPaletteSpecs.cinnabarDark,
    plum = CxPaletteSpecs.plumDark,
    isDark = true,
)

val LocalCxColors = staticCompositionLocalOf { LightCxColors }

object CxPalette {
    val canvas: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.canvas
    val surface: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.surface
    val surfaceSecondary: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.surfaceSecondary
    val border: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.border
    val ink: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.ink
    val inkSecondary: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.inkSecondary
    val inkTertiary: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.inkTertiary
    val accent: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.accent
    val onAccent: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.onAccent
    val sage: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.sage
    val amber: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.amber
    val cinnabar: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.cinnabar
    val plum: Color @Composable @ReadOnlyComposable get() = LocalCxColors.current.plum
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalCxColors.current.isDark
}

object CxTypography {
    val brandDisplay: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    )

    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    )

    val headline: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )

    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    )

    val caption: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    fun mono(
        size: TextUnit = 12.sp,
        weight: FontWeight = FontWeight.Medium,
    ): TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        fontSize = size,
    )
}

fun cxLightColorScheme(colors: CxColors = LightCxColors): ColorScheme = lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onPrimaryContainer = colors.accent,
    secondary = colors.inkSecondary,
    onSecondary = colors.onAccent,
    secondaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onSecondaryContainer = colors.accent,
    tertiary = colors.sage,
    onTertiary = colors.onAccent,
    tertiaryContainer = lerp(colors.surface, colors.sage, 0.13f),
    onTertiaryContainer = colors.sage,
    background = colors.canvas,
    onBackground = colors.ink,
    surface = colors.surface,
    onSurface = colors.ink,
    surfaceVariant = colors.surfaceSecondary,
    onSurfaceVariant = colors.inkSecondary,
    surfaceTint = Color.Transparent,
    outline = colors.border,
    outlineVariant = colors.border,
    error = colors.cinnabar,
    onError = colors.onAccent,
    errorContainer = lerp(colors.surface, colors.cinnabar, 0.13f),
    onErrorContainer = colors.cinnabar,
)

fun cxDarkColorScheme(colors: CxColors = DarkCxColors): ColorScheme = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = lerp(colors.surface, colors.accent, 0.16f),
    onPrimaryContainer = colors.accent,
    secondary = colors.inkSecondary,
    onSecondary = colors.onAccent,
    secondaryContainer = lerp(colors.surface, colors.accent, 0.16f),
    onSecondaryContainer = colors.accent,
    tertiary = colors.sage,
    onTertiary = colors.onAccent,
    tertiaryContainer = lerp(colors.surface, colors.sage, 0.16f),
    onTertiaryContainer = colors.sage,
    background = colors.canvas,
    onBackground = colors.ink,
    surface = colors.surface,
    onSurface = colors.ink,
    surfaceVariant = colors.surfaceSecondary,
    onSurfaceVariant = colors.inkSecondary,
    surfaceTint = Color.Transparent,
    outline = colors.border,
    outlineVariant = colors.border,
    error = colors.cinnabar,
    onError = colors.onAccent,
    errorContainer = lerp(colors.surface, colors.cinnabar, 0.16f),
    onErrorContainer = colors.cinnabar,
)

@Composable
fun CaochaoxingWindowsTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val cxColors = if (darkTheme) DarkCxColors else LightCxColors
    val colorScheme = if (darkTheme) cxDarkColorScheme(cxColors) else cxLightColorScheme(cxColors)

    CompositionLocalProvider(LocalCxColors provides cxColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
