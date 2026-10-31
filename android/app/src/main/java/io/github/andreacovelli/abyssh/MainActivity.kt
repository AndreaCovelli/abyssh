package io.github.andreacovelli.abyssh

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.ApplicationNavController
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import io.github.andreacovelli.abyssh.ui.theme.resolveActiveThemeName
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.getInstance(this)
        lifecycleScope.launch {
            settings.hideScreenContents.collect { hideScreenContents ->
                if (hideScreenContents) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000),
        )
        setContent {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    GhosttyThemeRegistry.init(context)
    val settings = SettingsRepository.getInstance(context)
    val fontName by settings.fontName.collectAsStateWithLifecycle()
    val themeName by settings.theme.darkThemeName.collectAsStateWithLifecycle()
    val themeMode by settings.theme.mode.collectAsStateWithLifecycle()
    val lightThemeName by settings.theme.lightThemeName.collectAsStateWithLifecycle()
    val pureBlack by settings.theme.pureBlack.collectAsStateWithLifecycle()
    val resolvedThemeName = resolveActiveThemeName(
        themeMode = themeMode,
        darkThemeName = themeName,
        lightThemeName = lightThemeName,
    )

    AbyssTheme(themeName = resolvedThemeName, fontName = fontName, pureBlack = pureBlack) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AbyssColors.current.background),
        ) {
            ApplicationNavController()
        }
    }
}
