package io.github.andreacovelli.abyssh.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.DEFAULT_WORD_SEPARATORS
import io.github.andreacovelli.abyssh.ui.terminal.ShortcutMenuLayout
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
    val shortcutMenuLayout: ShortcutMenuLayout = ShortcutMenuLayout(),
    val localShellEnabled: Boolean = true,
    val remoteClipboardWriteEnabled: Boolean = true,
    val ctrlVPastes: Boolean = true,
    val tmuxPrefixKeys: Boolean = false,
    val volumeKeysAsArrows: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val matchDisplayRefreshRate: Boolean = false,
    val hideScreenContents: Boolean = false,
    val disableAutocorrect: Boolean = true,
    val swipeTyping: Boolean = false,
    val echoPrediction: EchoPredictionMode = EchoPredictionMode.Default,
    val text: TerminalTextState = TerminalTextState(),
)

/** How the terminal treats its text, from [TerminalTextActions]. */
data class TerminalTextState(
    val wordSeparators: String = DEFAULT_WORD_SEPARATORS,
    val ligatures: Boolean = false,
    val cursorBlink: Boolean = false,
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
    fun setKeepScreenAwake(enabled: Boolean)
    fun setMatchDisplayRefreshRate(enabled: Boolean)
    fun setHideScreenContents(enabled: Boolean)
    fun setDisableAutocorrect(enabled: Boolean)
    fun setSwipeTyping(enabled: Boolean)
    fun setEchoPrediction(mode: EchoPredictionMode)
    val keys: TerminalKeyActions
    val text: TerminalTextActions
    val menu: ShortcutMenuActions
}

/** The order of the ⚡ shortcut menu and the items it hides. */
interface ShortcutMenuActions {
    fun setLayout(layout: ShortcutMenuLayout)
    fun setCustomKeyShown(key: Char, shown: Boolean)
}

/** How the terminal reads keys from a hardware keyboard. */
interface TerminalKeyActions {
    fun setCtrlVPastes(enabled: Boolean)
    fun setTmuxPrefixKeys(enabled: Boolean)
    fun setVolumeKeysAsArrows(enabled: Boolean)
}

interface TerminalTextActions {
    fun setWordSeparators(value: String)
    fun setLigatures(enabled: Boolean)
    fun setCursorBlink(enabled: Boolean)
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
                settings.shortcutMenu.layout,
                settings.localShellEnabled, settings.remoteClipboardWriteEnabled, settings.ctrlVPastes,
                settings.tmuxPrefixKeys,
                settings.keepScreenAwake, settings.matchDisplayRefreshRate, settings.hideScreenContents,
                settings.keyboard.disableAutocorrect, settings.keyboard.swipeTyping, settings.keyboard.echoPrediction,
                settings.volumeKeysAsArrows,
                settings.text.wordSeparators, settings.text.ligatures, settings.text.cursorBlink,
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
            shortcutMenuLayout = settings.shortcutMenu.layout.value,
            localShellEnabled = settings.localShellEnabled.value,
            remoteClipboardWriteEnabled = settings.remoteClipboardWriteEnabled.value,
            ctrlVPastes = settings.ctrlVPastes.value,
            tmuxPrefixKeys = settings.tmuxPrefixKeys.value,
            volumeKeysAsArrows = settings.volumeKeysAsArrows.value,
            keepScreenAwake = settings.keepScreenAwake.value,
            matchDisplayRefreshRate = settings.matchDisplayRefreshRate.value,
            hideScreenContents = settings.hideScreenContents.value,
            disableAutocorrect = settings.keyboard.disableAutocorrect.value,
            swipeTyping = settings.keyboard.swipeTyping.value,
            echoPrediction = settings.keyboard.echoPrediction.value,
            text = TerminalTextState(
                wordSeparators = settings.text.wordSeparators.value,
                ligatures = settings.text.ligatures.value,
                cursorBlink = settings.text.cursorBlink.value,
            ),
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
    override fun setKeepScreenAwake(enabled: Boolean) = settings.setKeepScreenAwake(enabled)
    override fun setMatchDisplayRefreshRate(enabled: Boolean) = settings.setMatchDisplayRefreshRate(enabled)
    override fun setHideScreenContents(enabled: Boolean) = settings.setHideScreenContents(enabled)
    override fun setDisableAutocorrect(enabled: Boolean) = settings.keyboard.setDisableAutocorrect(enabled)
    override fun setSwipeTyping(enabled: Boolean) = settings.keyboard.setSwipeTyping(enabled)
    override fun setEchoPrediction(mode: EchoPredictionMode) = settings.keyboard.setEchoPrediction(mode)

    override val keys = object : TerminalKeyActions {
        override fun setCtrlVPastes(enabled: Boolean) = settings.setCtrlVPastes(enabled)
        override fun setTmuxPrefixKeys(enabled: Boolean) = settings.setTmuxPrefixKeys(enabled)
        override fun setVolumeKeysAsArrows(enabled: Boolean) = settings.setVolumeKeysAsArrows(enabled)
    }

    override val text = object : TerminalTextActions {
        override fun setWordSeparators(value: String) = settings.text.setWordSeparators(value)
        override fun setLigatures(enabled: Boolean) = settings.text.setLigatures(enabled)
        override fun setCursorBlink(enabled: Boolean) = settings.text.setCursorBlink(enabled)
    }

    override val menu = object : ShortcutMenuActions {
        override fun setLayout(layout: ShortcutMenuLayout) = settings.shortcutMenu.setLayout(layout)
        override fun setCustomKeyShown(key: Char, shown: Boolean) = settings.shortcutMenu.setCustomKeyShown(key, shown)
    }

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
