package io.github.andreacovelli.abyssh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * Resolves the active terminal theme name based on the current mode and
 * system dark/light state.
 *
 * - [ThemeMode.Dark]   → [darkThemeName]
 * - [ThemeMode.Light]  → [lightThemeName]
 * - [ThemeMode.System] → [darkThemeName] when the device is in dark mode,
 *                       [lightThemeName] otherwise
 */
@Composable
fun resolveActiveThemeName(
    themeMode: ThemeMode,
    darkThemeName: String,
    lightThemeName: String,
): String = when (themeMode) {
    ThemeMode.Dark -> darkThemeName
    ThemeMode.Light -> lightThemeName
    ThemeMode.System -> if (isSystemInDarkTheme()) darkThemeName else lightThemeName
}
