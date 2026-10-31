package io.github.andreacovelli.abyssh.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Appearance and security settings shown on the General tab. */
data class GeneralSettingsState(
    val darkThemeName: String = SettingsRepository.DEFAULT_THEME,
    val lightThemeName: String = SettingsRepository.DEFAULT_LIGHT_THEME,
    val themeMode: ThemeMode = SettingsRepository.DEFAULT_THEME_MODE,
    val pureBlack: Boolean = false,
    val fontName: String = SettingsRepository.DEFAULT_FONT,
    val appLockEnabled: Boolean = false,
    val requireAuthOnConnect: Boolean = false,
)

/** Terminal behavior settings shown on the Terminal tab. */
data class TerminalSettingsState(
    val fontSize: Float = SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE,
    val scrollbackMb: Int = SettingsRepository.DEFAULT_TERMINAL_SCROLLBACK_MB,
    val tabMode: TerminalTabMode = TerminalTabMode.Classic,
    val accessoryLayoutIds: List<String> = emptyList(),
    val accessoryBarSingleRow: Boolean = false,
    val customKeyGroups: List<TerminalCustomKeyGroup> = emptyList(),
    val showCustomActionsFab: Boolean = false,
    val builtinShortcuts: Map<String, String> = emptyMap(),
    val localShellEnabled: Boolean = true,
    val remoteClipboardWriteEnabled: Boolean = true,
    val ctrlVPastes: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val hideScreenContents: Boolean = false,
    val disableAutocorrect: Boolean = true,
)

data class SettingsUiState(
    val general: GeneralSettingsState = GeneralSettingsState(),
    val terminal: TerminalSettingsState = TerminalSettingsState(),
)

interface GeneralSettingsActions {
    fun setDarkTheme(name: String)
    fun setLightTheme(name: String)
    fun setThemeMode(mode: ThemeMode)
    fun setPureBlack(enabled: Boolean)
    fun setFont(name: String)
    fun setAppLockEnabled(enabled: Boolean)
    fun setRequireAuthOnConnect(enabled: Boolean)
}

interface TerminalSettingsActions {
    fun setFontSize(sizeSp: Float)
    fun setScrollbackMb(megabytes: Int)
    fun setTabMode(mode: TerminalTabMode)
    fun setAccessoryLayout(ids: List<String>)
    fun setAccessoryBarSingleRow(enabled: Boolean)
    fun setCustomKeyGroups(groups: List<TerminalCustomKeyGroup>)
    fun setShowCustomActionsFab(visible: Boolean)
    fun setBuiltinShortcuts(shortcuts: Map<String, String>)
    fun setLocalShellEnabled(enabled: Boolean)
    fun setRemoteClipboardWriteEnabled(enabled: Boolean)
    fun setCtrlVPastes(enabled: Boolean)
    fun setKeepScreenAwake(enabled: Boolean)
    fun setHideScreenContents(enabled: Boolean)
    fun setDisableAutocorrect(enabled: Boolean)
}

class SettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel(),
    GeneralSettingsActions,
    TerminalSettingsActions {

    val uiState: StateFlow<SettingsUiState> =
        // Every source is a StateFlow, so each emission rebuilds the state from their
        // current values rather than threading twenty typed arguments through combine.
        combine(
            listOf(
                settings.theme.darkThemeName, settings.theme.lightThemeName, settings.theme.mode, settings.theme.pureBlack,
                settings.fontName,
                settings.appLockEnabled, settings.requireAuthOnConnect, settings.terminalFontSize,
                settings.terminalScrollbackMb,
                settings.terminalTabMode, settings.accessoryLayoutIds, settings.accessoryBarSingleRow,
                settings.terminalCustomKeyGroups, settings.showCustomActionsFab, settings.builtinShortcuts,
                settings.localShellEnabled, settings.remoteClipboardWriteEnabled, settings.ctrlVPastes,
                settings.keepScreenAwake, settings.hideScreenContents, settings.disableAutocorrect,
            ),
        ) { currentState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), currentState())

    private fun currentState() = SettingsUiState(
        general = GeneralSettingsState(
            darkThemeName = settings.theme.darkThemeName.value,
            lightThemeName = settings.theme.lightThemeName.value,
            themeMode = settings.theme.mode.value,
            pureBlack = settings.theme.pureBlack.value,
            fontName = settings.fontName.value,
            appLockEnabled = settings.appLockEnabled.value,
            requireAuthOnConnect = settings.requireAuthOnConnect.value,
        ),
        terminal = TerminalSettingsState(
            fontSize = settings.terminalFontSize.value,
            scrollbackMb = settings.terminalScrollbackMb.value,
            tabMode = settings.terminalTabMode.value,
            accessoryLayoutIds = settings.accessoryLayoutIds.value,
            accessoryBarSingleRow = settings.accessoryBarSingleRow.value,
            customKeyGroups = settings.terminalCustomKeyGroups.value,
            showCustomActionsFab = settings.showCustomActionsFab.value,
            builtinShortcuts = settings.builtinShortcuts.value,
            localShellEnabled = settings.localShellEnabled.value,
            remoteClipboardWriteEnabled = settings.remoteClipboardWriteEnabled.value,
            ctrlVPastes = settings.ctrlVPastes.value,
            keepScreenAwake = settings.keepScreenAwake.value,
            hideScreenContents = settings.hideScreenContents.value,
            disableAutocorrect = settings.disableAutocorrect.value,
        ),
    )

    override fun setDarkTheme(name: String) = settings.theme.setDarkTheme(name)
    override fun setLightTheme(name: String) = settings.theme.setLightTheme(name)
    override fun setThemeMode(mode: ThemeMode) = settings.theme.setMode(mode)
    override fun setPureBlack(enabled: Boolean) = settings.theme.setPureBlack(enabled)
    override fun setFont(name: String) = settings.setFont(name)
    override fun setAppLockEnabled(enabled: Boolean) = settings.setAppLockEnabled(enabled)
    override fun setRequireAuthOnConnect(enabled: Boolean) = settings.setRequireAuthOnConnect(enabled)

    override fun setFontSize(sizeSp: Float) = settings.setTerminalFontSize(sizeSp)
    override fun setScrollbackMb(megabytes: Int) = settings.setTerminalScrollbackMb(megabytes)
    override fun setTabMode(mode: TerminalTabMode) = settings.setTerminalTabMode(mode)
    override fun setAccessoryLayout(ids: List<String>) = settings.setAccessoryLayoutIds(ids)
    override fun setAccessoryBarSingleRow(enabled: Boolean) = settings.setAccessoryBarSingleRow(enabled)
    override fun setCustomKeyGroups(groups: List<TerminalCustomKeyGroup>) = settings.setTerminalCustomKeyGroups(groups)
    override fun setShowCustomActionsFab(visible: Boolean) = settings.setShowCustomActionsFab(visible)
    override fun setBuiltinShortcuts(shortcuts: Map<String, String>) = settings.setBuiltinShortcuts(shortcuts)
    override fun setLocalShellEnabled(enabled: Boolean) = settings.setLocalShellEnabled(enabled)
    override fun setRemoteClipboardWriteEnabled(enabled: Boolean) = settings.setRemoteClipboardWriteEnabled(enabled)
    override fun setCtrlVPastes(enabled: Boolean) = settings.setCtrlVPastes(enabled)
    override fun setKeepScreenAwake(enabled: Boolean) = settings.setKeepScreenAwake(enabled)
    override fun setHideScreenContents(enabled: Boolean) = settings.setHideScreenContents(enabled)
    override fun setDisableAutocorrect(enabled: Boolean) = settings.setDisableAutocorrect(enabled)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return SettingsViewModel(SettingsRepository.getInstance(application)) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
