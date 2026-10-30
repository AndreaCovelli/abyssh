package io.github.andreacovelli.abyssh.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinShortcutStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.theme.AbyssFontOption
import io.github.andreacovelli.abyssh.ui.theme.ThemeMode
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomActionStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val legacyTerminalPrefs: SharedPreferences =
        context.getSharedPreferences(LEGACY_TERMINAL_PREFS, Context.MODE_PRIVATE)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("abyssh_settings", Context.MODE_PRIVATE)

    private val _themeName = MutableStateFlow(prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME)
    val themeName: StateFlow<String> = _themeName.asStateFlow()

    private val _fontName = MutableStateFlow(
        AbyssFontOption.fromId(prefs.getString(KEY_FONT, DEFAULT_FONT)).id,
    )
    val fontName: StateFlow<String> = _fontName.asStateFlow()

    private val _accessoryLayoutIds = MutableStateFlow(loadAccessoryLayoutIds())
    val accessoryLayoutIds: StateFlow<List<String>> = _accessoryLayoutIds.asStateFlow()

    private val _terminalCustomKeyGroups = MutableStateFlow(loadTerminalCustomKeyGroups())
    val terminalCustomKeyGroups: StateFlow<List<TerminalCustomKeyGroup>> = _terminalCustomKeyGroups.asStateFlow()

    private val _showCustomActionsFab = MutableStateFlow(prefs.getBoolean(KEY_SHOW_CUSTOM_ACTIONS_FAB, false))
    val showCustomActionsFab: StateFlow<Boolean> = _showCustomActionsFab.asStateFlow()

    private val _builtinShortcuts = MutableStateFlow(loadBuiltinShortcuts())
    val builtinShortcuts: StateFlow<Map<String, String>> = _builtinShortcuts.asStateFlow()

    private val _accessoryBarSingleRow = MutableStateFlow(prefs.getBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, false))
    val accessoryBarSingleRow: StateFlow<Boolean> = _accessoryBarSingleRow.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK_ENABLED, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _requireAuthOnConnect = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, false))
    val requireAuthOnConnect: StateFlow<Boolean> = _requireAuthOnConnect.asStateFlow()

    private val _localShellEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_SHELL_ENABLED, true))
    val localShellEnabled: StateFlow<Boolean> = _localShellEnabled.asStateFlow()

    private val _keepScreenAwake = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false))
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    private val _hideScreenContents = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SCREEN_CONTENTS, false))
    val hideScreenContents: StateFlow<Boolean> = _hideScreenContents.asStateFlow()

    private val _disableAutocorrect = MutableStateFlow(prefs.getBoolean(KEY_DISABLE_AUTOCORRECT, true))
    val disableAutocorrect: StateFlow<Boolean> = _disableAutocorrect.asStateFlow()
    private val _remoteClipboardWriteEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_REMOTE_CLIPBOARD_WRITE_ENABLED, true))
    val remoteClipboardWriteEnabled: StateFlow<Boolean> = _remoteClipboardWriteEnabled.asStateFlow()
    private val _ctrlVPastes = MutableStateFlow(prefs.getBoolean(KEY_CTRL_V_PASTES, true))
    val ctrlVPastes: StateFlow<Boolean> = _ctrlVPastes.asStateFlow()

    private val _terminalTabMode = MutableStateFlow(
        parseTabMode(prefs.getString(KEY_TAB_MODE, TerminalTabMode.Classic.name)),
    )
    val terminalTabMode: StateFlow<TerminalTabMode> = _terminalTabMode.asStateFlow()

    private val _themeMode = MutableStateFlow(parseThemeMode(prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE.name)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _lightThemeName = MutableStateFlow(
        prefs.getString(KEY_LIGHT_THEME, DEFAULT_LIGHT_THEME) ?: DEFAULT_LIGHT_THEME,
    )
    val lightThemeName: StateFlow<String> = _lightThemeName.asStateFlow()

    private val _terminalFontSize = MutableStateFlow(
        loadTerminalFontSize(),
    )
    val terminalFontSize: StateFlow<Float> = _terminalFontSize.asStateFlow()

    private val _terminalScrollbackMb = MutableStateFlow(
        prefs.getInt(KEY_TERMINAL_SCROLLBACK_MB, DEFAULT_TERMINAL_SCROLLBACK_MB)
            .coerceIn(MIN_TERMINAL_SCROLLBACK_MB, MAX_TERMINAL_SCROLLBACK_MB),
    )

    /** The scrollback each terminal may keep, in megabytes; it applies when a tab connects. */
    val terminalScrollbackMb: StateFlow<Int> = _terminalScrollbackMb.asStateFlow()

    fun setTheme(name: String) {
        prefs.edit { putString(KEY_THEME, name) }
        _themeName.value = name
    }

    fun setFont(name: String) {
        val normalized = AbyssFontOption.fromId(name).id
        prefs.edit { putString(KEY_FONT, normalized) }
        _fontName.value = normalized
    }

    fun setAccessoryLayoutIds(ids: List<String>) {
        val normalized = TerminalAccessoryLayoutStore.normalizeIds(ids)
        prefs.edit { putString(KEY_ACCESSORY_LAYOUT, normalized.joinToString(separator = ",")) }
        _accessoryLayoutIds.value = normalized
    }

    fun setTerminalCustomKeyGroups(groups: List<TerminalCustomKeyGroup>) {
        val normalized = TerminalCustomActionStore.normalize(groups)
        val serialized = TerminalCustomActionStore.serialize(normalized)
        prefs.edit { putString(KEY_TERMINAL_CUSTOM_ACTIONS, serialized) }
        _terminalCustomKeyGroups.value = normalized
    }

    fun setShowCustomActionsFab(visible: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_CUSTOM_ACTIONS_FAB, visible) }
        _showCustomActionsFab.value = visible
    }

    fun setBuiltinShortcuts(shortcuts: Map<String, String>) {
        val normalized = BuiltinShortcutStore.normalize(shortcuts)
        prefs.edit { putString(KEY_BUILTIN_SHORTCUTS, BuiltinShortcutStore.serialize(normalized)) }
        _builtinShortcuts.value = normalized
    }

    fun setAccessoryBarSingleRow(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, enabled) }
        _accessoryBarSingleRow.value = enabled
    }

    fun setTerminalTabMode(mode: TerminalTabMode) {
        prefs.edit { putString(KEY_TAB_MODE, mode.name) }
        _terminalTabMode.value = mode
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_APP_LOCK_ENABLED, enabled) }
        _appLockEnabled.value = enabled
    }

    fun setRequireAuthOnConnect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, enabled) }
        _requireAuthOnConnect.value = enabled
    }

    fun setLocalShellEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOCAL_SHELL_ENABLED, enabled) }
        _localShellEnabled.value = enabled
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled) }
        _keepScreenAwake.value = enabled
    }

    fun setHideScreenContents(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_SCREEN_CONTENTS, enabled) }
        _hideScreenContents.value = enabled
    }

    fun setDisableAutocorrect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DISABLE_AUTOCORRECT, enabled) }
        _disableAutocorrect.value = enabled
    }

    fun setRemoteClipboardWriteEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REMOTE_CLIPBOARD_WRITE_ENABLED, enabled) }
        _remoteClipboardWriteEnabled.value = enabled
    }

    fun setCtrlVPastes(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CTRL_V_PASTES, enabled) }
        _ctrlVPastes.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
    }

    fun setLightTheme(name: String) {
        prefs.edit { putString(KEY_LIGHT_THEME, name) }
        _lightThemeName.value = name
    }

    fun setTerminalFontSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)
        prefs.edit {
            putFloat(KEY_TERMINAL_FONT_SIZE, clamped)
            putBoolean(KEY_TERMINAL_FONT_SIZE_CUSTOMIZED, true)
        }
        _terminalFontSize.value = clamped
    }

    fun setTerminalScrollbackMb(megabytes: Int) {
        val clamped = megabytes.coerceIn(MIN_TERMINAL_SCROLLBACK_MB, MAX_TERMINAL_SCROLLBACK_MB)
        prefs.edit { putInt(KEY_TERMINAL_SCROLLBACK_MB, clamped) }
        _terminalScrollbackMb.value = clamped
    }

    private fun loadTerminalFontSize(): Float {
        val stored =
            if (prefs.getBoolean(KEY_TERMINAL_FONT_SIZE_CUSTOMIZED, false)) {
                prefs.getFloat(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE)
            } else {
                legacyTerminalPrefs.getFloat(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE)
            }
        return stored.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)
    }

    private fun loadAccessoryLayoutIds(): List<String> {
        val stored = prefs.getString(KEY_ACCESSORY_LAYOUT, null)
            ?: return TerminalAccessoryLayoutStore.defaultLayoutIds()
        if (stored.isBlank()) {
            return emptyList()
        }
        return TerminalAccessoryLayoutStore.normalizeIds(
            stored.split(',').map(String::trim).filter(String::isNotEmpty),
        )
    }

    private fun loadTerminalCustomKeyGroups(): List<TerminalCustomKeyGroup> {
        val stored = prefs.getString(KEY_TERMINAL_CUSTOM_ACTIONS, null)
        return TerminalCustomActionStore.parse(stored)
    }

    private fun loadBuiltinShortcuts(): Map<String, String> {
        val stored = prefs.getString(KEY_BUILTIN_SHORTCUTS, null)
        return BuiltinShortcutStore.parse(stored)
    }

    companion object {
        private const val KEY_THEME = "theme_name"
        private const val KEY_FONT = "font_name"
        private const val KEY_ACCESSORY_LAYOUT = "terminal_accessory_layout"
        private const val KEY_TERMINAL_CUSTOM_ACTIONS = "terminal_custom_actions"
        private const val KEY_SHOW_CUSTOM_ACTIONS_FAB = "show_custom_actions_fab"
        private const val KEY_BUILTIN_SHORTCUTS = "builtin_shortcuts"
        private const val KEY_ACCESSORY_BAR_SINGLE_ROW = "terminal_accessory_bar_single_row"
        private const val KEY_TAB_MODE = "terminal_tab_mode"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_REQUIRE_AUTH_ON_CONNECT = "require_auth_on_connect"
        private const val KEY_LOCAL_SHELL_ENABLED = "local_shell_enabled"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
        private const val KEY_HIDE_SCREEN_CONTENTS = "hide_screen_contents"
        private const val KEY_DISABLE_AUTOCORRECT = "disable_autocorrect"
        private const val KEY_REMOTE_CLIPBOARD_WRITE_ENABLED = "remote_clipboard_write_enabled"
        private const val KEY_CTRL_V_PASTES = "ctrl_v_pastes"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LIGHT_THEME = "light_theme_name"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size_sp"
        private const val KEY_TERMINAL_FONT_SIZE_CUSTOMIZED = "terminal_font_size_customized"
        private const val KEY_TERMINAL_SCROLLBACK_MB = "terminal_scrollback_mb"
        private const val LEGACY_TERMINAL_PREFS = "abyssh_terminal"
        const val DEFAULT_THEME = "Catppuccin Mocha"
        const val DEFAULT_LIGHT_THEME = "Catppuccin Latte"
        val DEFAULT_THEME_MODE = ThemeMode.System
        const val DEFAULT_FONT = "jetbrains_mono"
        const val DEFAULT_TERMINAL_FONT_SIZE = 14f
        const val MIN_TERMINAL_FONT_SIZE = 6f
        const val MAX_TERMINAL_FONT_SIZE = 72f

        // Megabytes of 1,000,000 bytes, like Ghostty's scrollback-limit, whose default is 10 MB.
        const val DEFAULT_TERMINAL_SCROLLBACK_MB = 10
        const val MIN_TERMINAL_SCROLLBACK_MB = 1
        const val MAX_TERMINAL_SCROLLBACK_MB = 50
        val TERMINAL_SCROLLBACK_OPTIONS_MB = listOf(1, 10, 25, 50)
        const val BYTES_PER_MB = 1_000_000

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository = instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }

        private fun parseThemeMode(value: String?): ThemeMode =
            ThemeMode.entries.find { it.name == value } ?: DEFAULT_THEME_MODE

        private fun parseTabMode(value: String?): TerminalTabMode =
            try {
                TerminalTabMode.valueOf(value ?: TerminalTabMode.Classic.name)
            } catch (_: IllegalArgumentException) {
                TerminalTabMode.Classic
            }

    }
}
