package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.AutoClearFocusBehavior
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.ResolvedAccessoryEntry
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.GhosttyTheme
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import io.github.andreacovelli.abyssh.ui.theme.resolveActiveThemeName
import io.github.andreacovelli.abyssh.ui.theme.withPureBlack

/** The settings the terminal screen reads, collected in one place. */
@Immutable
internal data class TerminalUiSettings(
    val ghosttyTheme: GhosttyTheme?,
    val tabMode: TerminalTabMode,
    val accessoryLayout: List<ResolvedAccessoryEntry>,
    val singleRowAccessoryBar: Boolean,
    val customKeyGroups: List<TerminalCustomKeyGroup>,
    val builtinShortcuts: Map<String, String>,
    val keepScreenAwake: Boolean,
    val disableAutocorrect: Boolean,
    val ctrlVPastes: Boolean,
    val showCustomActionsFab: Boolean,
)

@Composable
internal fun rememberTerminalUiSettings(repo: SettingsRepository): TerminalUiSettings {
    val context = LocalContext.current
    val darkThemeName by repo.theme.darkThemeName.collectAsStateWithLifecycle()
    val themeMode by repo.theme.mode.collectAsStateWithLifecycle()
    val lightThemeName by repo.theme.lightThemeName.collectAsStateWithLifecycle()
    val themeName = resolveActiveThemeName(themeMode, darkThemeName, lightThemeName)
    val pureBlack by repo.theme.pureBlack.collectAsStateWithLifecycle()
    val ghosttyTheme = remember(context, themeName, pureBlack) {
        GhosttyThemeRegistry.getTheme(context, themeName)?.let { if (pureBlack) it.withPureBlack() else it }
    }
    val tabMode by repo.terminalTabMode.collectAsStateWithLifecycle()
    val layoutIds by repo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val accessoryLayout = remember(layoutIds) { TerminalAccessoryLayoutStore.resolveSelectedLayout(layoutIds) }
    val singleRow by repo.accessoryBarSingleRow.collectAsStateWithLifecycle()
    val customKeyGroups by repo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
    val builtinShortcuts by repo.builtinShortcuts.collectAsStateWithLifecycle()
    val keepScreenAwake by repo.keepScreenAwake.collectAsStateWithLifecycle()
    val disableAutocorrect by repo.disableAutocorrect.collectAsStateWithLifecycle()
    val ctrlVPastes by repo.ctrlVPastes.collectAsStateWithLifecycle()
    val showCustomActionsFab by repo.showCustomActionsFab.collectAsStateWithLifecycle()
    return TerminalUiSettings(
        ghosttyTheme = ghosttyTheme,
        tabMode = tabMode,
        accessoryLayout = accessoryLayout,
        singleRowAccessoryBar = singleRow,
        customKeyGroups = customKeyGroups,
        builtinShortcuts = builtinShortcuts,
        keepScreenAwake = keepScreenAwake,
        disableAutocorrect = disableAutocorrect,
        ctrlVPastes = ctrlVPastes,
        showCustomActionsFab = showCustomActionsFab,
    )
}

/** The terminal font size: pinch zoom changes it here and in settings. */
@Composable
internal fun rememberTerminalFontSize(repo: SettingsRepository): MutableFloatState {
    val stored by repo.terminalFontSize.collectAsStateWithLifecycle()
    val size = remember { mutableFloatStateOf(clampFontSize(stored)) }
    LaunchedEffect(stored) { size.floatValue = clampFontSize(stored) }
    LaunchedEffect(size.floatValue) { repo.setTerminalFontSize(size.floatValue) }
    return size
}

private fun clampFontSize(sizeSp: Float): Float =
    sizeSp.coerceIn(SettingsRepository.MIN_TERMINAL_FONT_SIZE, SettingsRepository.MAX_TERMINAL_FONT_SIZE)

@Composable
internal fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val window = (view.context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Keeps a mouse press on the terminal from clearing focus. Compose clears focus when a
 * mouse press lands outside the focused node, which here is the 1 dp input view, so every
 * click told the remote app the terminal lost focus until the button came up. Apps such as
 * Claude Code then dropped the drag selection made in between.
 */
@Composable
internal fun KeepFocusOnMouseClickEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val host = view.parent as? ComposeView
        val previous = host?.autoClearFocusBehavior
        host?.autoClearFocusBehavior = AutoClearFocusBehavior.None
        onDispose { if (host != null && previous != null) host.autoClearFocusBehavior = previous }
    }
}

/** The shortcut prefix bindings: builtin commands first, then the custom actions. */
@Composable
internal fun rememberAbysshKeys(
    vm: TerminalViewModel,
    repo: SettingsRepository,
    settings: TerminalUiSettings,
    overlay: TerminalOverlayState,
    onOpenSettings: () -> Unit,
): AbysshKeyBindings {
    val openSettings by rememberUpdatedState(onOpenSettings)
    return remember(
        vm,
        settings.tabMode,
        settings.customKeyGroups,
        settings.builtinShortcuts,
        settings.showCustomActionsFab,
    ) {
        val handlers: Map<BuiltinCommand, () -> Unit> = mapOf(
            BuiltinCommand.Tabs to { overlay.openSwitcher(settings.tabMode) },
            BuiltinCommand.NewTab to {
                vm.duplicateTab()
                vm.files.selectPane(ConnectionTab.Terminal)
                overlay.showTabSheet = false
            },
            BuiltinCommand.Close to { vm.activeTabId.value?.let(vm::closeTab) },
            BuiltinCommand.Actions to { repo.setShowCustomActionsFab(!settings.showCustomActionsFab) },
            BuiltinCommand.Settings to { openSettings() },
            BuiltinCommand.Search to {
                vm.files.selectPane(ConnectionTab.Terminal)
                vm.search.open()
            },
            BuiltinCommand.Zoom to {
                vm.files.selectPane(ConnectionTab.Terminal)
                overlay.zoom.toggle()
            },
        )
        AbysshKeyBindings.build(
            builtinShortcuts = settings.builtinShortcuts,
            builtinCommandHandlers = handlers,
            customGroups = settings.customKeyGroups,
            onDispatchAction = vm.input::sendCustomAction,
            onSelectAmongActions = { actions -> overlay.fabFilteredActions = actions },
        )
    }
}
