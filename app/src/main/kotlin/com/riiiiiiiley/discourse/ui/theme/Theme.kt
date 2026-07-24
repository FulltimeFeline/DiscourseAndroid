package com.riiiiiiiley.discourse.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** The brand seed — the icon purple (#9059F1). Used when dynamic color is off. */
val AppAccent = Color(0xFF9059F1)

/**
 * Discourse design tokens. Feature code reads these — never hard-coded colors.
 * They are now DERIVED from a Material 3 [ColorScheme] (Material You), so the
 * whole app follows the platform's dynamic wallpaper palette on Android 12+ and
 * a brand-seeded M3 scheme below that. The field set is unchanged, so every
 * feature screen inherits the new look without edits.
 */
data class DiscourseColors(
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val bgApp: Color,
    val bgRail: Color,
    val bgSidebar: Color,
    val bgElevated: Color,
    val bgElevated2: Color,
    val bgInput: Color,
    val bgHover: Color,
    val bgBubbleIn: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    val separator: Color,
    val unreadMention: Color,
    val presenceOnline: Color,
    val isDark: Boolean,
)

/**
 * Maps a Material 3 scheme onto our tokens. Surfaces use the M3 tonal
 * surface-container roles (the Material You elevation ladder) so rail, sidebar,
 * and elevated sheets read as distinct tonal steps of the wallpaper palette
 * instead of the old fixed near-blacks.
 */
private fun tokensFrom(scheme: ColorScheme, dark: Boolean) = DiscourseColors(
    accent = scheme.primary,
    accentStrong = scheme.primaryContainer,
    accentSoft = scheme.primary.copy(alpha = 0.16f),
    bgApp = scheme.background,
    bgRail = scheme.surfaceContainerLowest,
    bgSidebar = scheme.surfaceContainerLow,
    bgElevated = scheme.surfaceContainer,
    bgElevated2 = scheme.surfaceContainerHigh,
    bgInput = scheme.surfaceContainerHighest,
    bgHover = scheme.onSurface.copy(alpha = if (dark) 0.06f else 0.045f),
    bgBubbleIn = scheme.surfaceContainerHigh,
    textPrimary = scheme.onBackground,
    textSecondary = scheme.onSurfaceVariant,
    textTertiary = scheme.outline,
    textOnAccent = scheme.onPrimary,
    separator = scheme.outlineVariant,
    // Kept semantic (Material harmonization could tint these later).
    unreadMention = Color(0xFFFF453A),
    presenceOnline = Color(0xFF34C759),
    isDark = dark,
)

/**
 * Brand-seeded M3 scheme for pre-12 devices (and when dynamic color is off):
 * the accent becomes `primary`, and the neutral surfaces are lightly tinted
 * toward it so the app still carries its purple identity as a Material You-style
 * tonal palette rather than flat gray.
 */
private fun brandScheme(accent: Color, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun tint(c: Color, amount: Float) = lerp(c, accent, amount)
    val s = if (dark) 1f else 0.7f
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = lerp(accent, Color.Black, if (dark) 0.45f else 0.0f),
        background = tint(base.background, 0.05f * s),
        surface = tint(base.surface, 0.05f * s),
        surfaceContainerLowest = tint(base.surfaceContainerLowest, 0.06f * s),
        surfaceContainerLow = tint(base.surfaceContainerLow, 0.06f * s),
        surfaceContainer = tint(base.surfaceContainer, 0.07f * s),
        surfaceContainerHigh = tint(base.surfaceContainerHigh, 0.07f * s),
        surfaceContainerHighest = tint(base.surfaceContainerHighest, 0.08f * s),
        surfaceVariant = tint(base.surfaceVariant, 0.06f * s),
    )
}

val LocalDiscourseColors = staticCompositionLocalOf {
    tokensFrom(brandScheme(AppAccent, dark = true), dark = true)
}

@Composable
fun DiscourseTheme(
    accent: Color = AppAccent,
    /** Follow the OS wallpaper palette (Material You) when available. */
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> brandScheme(accent, darkTheme)
    }
    val colors = tokensFrom(scheme, darkTheme)
    CompositionLocalProvider(LocalDiscourseColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
