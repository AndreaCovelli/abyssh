package io.github.andreacovelli.abyssh.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun AbyssTheme(
    themeName: String? = null,
    fontName: String? = null,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = themeName
        ?.let { GhosttyThemeRegistry.getTheme(context, it) }
        ?.toChuColorPalette(pureBlack)
        ?: AbyssDarkColors

    val fontOption = remember(fontName) { AbyssFontOption.fromId(fontName) }
    val typography = remember(fontOption) { abyssTypographyFor(fontOption) }
    val colorScheme = remember(palette) { palette.toColorScheme() }
    val materialTypography = remember(typography) { typography.toMaterialTypography() }

    // Drive status- and navigation-bar icon brightness from the active theme's
    // background. Without this, the system clock/wifi/battery icons stay white
    // and become unreadable on every light theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val isLightBackground = palette.background.perceivedLuminance() >= 0.5f
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = isLightBackground
            controller.isAppearanceLightNavigationBars = isLightBackground
        }
    }

    CompositionLocalProvider(
        LocalChuColors provides palette,
        LocalChuFont provides fontOption,
        LocalChuTypography provides typography,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = materialTypography) {
            CompositionLocalProvider(LocalContentColor provides palette.textPrimary, content = content)
        }
    }
}
