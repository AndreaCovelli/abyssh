package io.github.andreacovelli.abyssh.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle

/**
 * Maps the active Ghostty palette onto a Material 3 color scheme, so Material
 * components follow the terminal theme.
 */
fun AbyssColorPalette.toColorScheme(): ColorScheme {
    val isLight = background.perceivedLuminance() >= 0.5f
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    val accentContainer = lerp(background, accent, 0.24f)
    val secondaryContainer = lerp(background, accentSecondary, 0.24f)
    val tertiaryContainer = lerp(background, success, 0.24f)
    val errorContainer = lerp(background, error, 0.24f)
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = textPrimary,
        inversePrimary = lerp(accent, textPrimary, 0.5f),
        secondary = accentSecondary,
        onSecondary = onAccent,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = textPrimary,
        tertiary = success,
        onTertiary = onAccent,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = textPrimary,
        background = background,
        onBackground = textPrimary,
        surface = background,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        error = error,
        onError = onAccent,
        errorContainer = errorContainer,
        onErrorContainer = textPrimary,
        outline = border,
        outlineVariant = lerp(border, background, 0.5f),
        scrim = Color.Black,
        surfaceBright = lerp(surface, textPrimary, 0.06f),
        surfaceDim = surfaceVariant,
        surfaceContainerLowest = surfaceVariant,
        surfaceContainerLow = lerp(background, surface, 0.35f),
        surfaceContainer = lerp(background, surface, 0.6f),
        surfaceContainerHigh = surface,
        surfaceContainerHighest = lerp(surface, textPrimary, 0.06f),
    )
}

/** Material 3 type scale set in the user's chosen monospace font. */
fun AbyssTypeScale.toMaterialTypography(): Typography {
    val base = Typography()
    val family = body.fontFamily
    fun TextStyle.mono() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.mono(),
        displayMedium = base.displayMedium.mono(),
        displaySmall = base.displaySmall.mono(),
        headlineLarge = base.headlineLarge.mono(),
        headlineMedium = base.headlineMedium.mono(),
        headlineSmall = headline,
        titleLarge = base.titleLarge.mono(),
        titleMedium = title,
        titleSmall = base.titleSmall.mono(),
        bodyLarge = base.bodyLarge.mono(),
        bodyMedium = body,
        bodySmall = bodySmall,
        labelLarge = label,
        labelMedium = base.labelMedium.mono(),
        labelSmall = labelSmall,
    )
}

internal fun Color.perceivedLuminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
