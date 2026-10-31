package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The theme settings: the dark and light themes, which one shows, and pure black. */
class ThemeSettings internal constructor(private val prefs: SharedPreferences) {

    private val _darkThemeName = MutableStateFlow(
        prefs.getString(KEY_THEME, SettingsRepository.DEFAULT_THEME) ?: SettingsRepository.DEFAULT_THEME,
    )
    val darkThemeName: StateFlow<String> = _darkThemeName.asStateFlow()

    private val _lightThemeName = MutableStateFlow(
        prefs.getString(KEY_LIGHT_THEME, SettingsRepository.DEFAULT_LIGHT_THEME)
            ?: SettingsRepository.DEFAULT_LIGHT_THEME,
    )
    val lightThemeName: StateFlow<String> = _lightThemeName.asStateFlow()

    private val _mode = MutableStateFlow(parseThemeMode(prefs.getString(KEY_THEME_MODE, null)))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _pureBlack = MutableStateFlow(prefs.getBoolean(KEY_PURE_BLACK, false))

    /** Whether dark themes draw on a pure black background, for OLED screens. */
    val pureBlack: StateFlow<Boolean> = _pureBlack.asStateFlow()

    fun setDarkTheme(name: String) {
        prefs.edit { putString(KEY_THEME, name) }
        _darkThemeName.value = name
    }

    fun setLightTheme(name: String) {
        prefs.edit { putString(KEY_LIGHT_THEME, name) }
        _lightThemeName.value = name
    }

    fun setMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _mode.value = mode
    }

    fun setPureBlack(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PURE_BLACK, enabled) }
        _pureBlack.value = enabled
    }

    private companion object {
        const val KEY_THEME = "theme_name"
        const val KEY_LIGHT_THEME = "light_theme_name"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_PURE_BLACK = "pure_black"

        fun parseThemeMode(value: String?): ThemeMode =
            ThemeMode.entries.find { it.name == value } ?: SettingsRepository.DEFAULT_THEME_MODE
    }
}
