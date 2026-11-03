package io.github.andreacovelli.abyssh

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.ApplicationNavController
import io.github.andreacovelli.abyssh.ui.EscapeKeyBack
import io.github.andreacovelli.abyssh.ui.LocalEscapeKeyBack
import io.github.andreacovelli.abyssh.ui.terminal.hasPhysicalKeyboard
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import io.github.andreacovelli.abyssh.ui.theme.resolveActiveThemeName
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val escapeKeyBack by lazy { EscapeKeyBack(onBackPressedDispatcher) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        escapeKeyBack.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        escapeKeyBack.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSoftInputState(resources.configuration)
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
            CompositionLocalProvider(LocalEscapeKeyBack provides escapeKeyBack) {
                AppRoot()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSoftInputState(newConfig)
    }

    /**
     * Keeps the on-screen keyboard hidden when the window gains focus while a physical
     * keyboard is connected. Otherwise Android shows it on its own when the app returns
     * to the front on a large screen, and again on every return after that, leaving the
     * keyboard's empty strip with its hide and switch buttons under the terminal.
     */
    private fun updateSoftInputState(config: Configuration) {
        val state = if (config.hasPhysicalKeyboard()) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        val adjust = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        window.setSoftInputMode(state or adjust)
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
