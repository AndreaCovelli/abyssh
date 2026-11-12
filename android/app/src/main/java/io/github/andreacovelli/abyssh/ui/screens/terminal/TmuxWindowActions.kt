package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TmuxWindowAction
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** What a tmux window command does: an action tmux runs, or null to rename the window, which asks for a name first. */
internal val TmuxWindowCommands: List<Pair<BuiltinCommand, TmuxWindowAction?>> = listOf(
    BuiltinCommand.SplitRight to TmuxWindowAction.SplitRight,
    BuiltinCommand.SplitDown to TmuxWindowAction.SplitDown,
    BuiltinCommand.ClosePane to TmuxWindowAction.ClosePane,
    BuiltinCommand.ZoomPane to TmuxWindowAction.ZoomPane,
    BuiltinCommand.KillWindow to TmuxWindowAction.KillWindow,
    BuiltinCommand.RenameWindow to null,
    BuiltinCommand.PreviousWindow to TmuxWindowAction.PreviousWindow,
    BuiltinCommand.NextWindow to TmuxWindowAction.NextWindow,
    BuiltinCommand.Detach to TmuxWindowAction.Detach,
)

/** The shortcut handlers for the tmux window commands, for a tab that shows a tmux window. */
internal fun tmuxCommandHandlers(vm: TerminalViewModel, overlay: TerminalOverlayState): Map<BuiltinCommand, () -> Unit> =
    TmuxWindowCommands.associate { (command, action) -> command to { runTmuxCommand(vm, overlay, action) } }

/**
 * Runs [action] on the active tab's tmux window. Asks for a new name when [action] is
 * null, and asks first before killing the window, which ends the programs in it.
 */
internal fun runTmuxCommand(vm: TerminalViewModel, overlay: TerminalOverlayState, action: TmuxWindowAction?) {
    val name = vm.activeTab.value?.let(::tmuxWindowName).orEmpty()
    when (action) {
        null -> overlay.renamingWindow = name
        TmuxWindowAction.KillWindow -> overlay.killingWindow = name
        else -> vm.runTmuxAction(action)
    }
}

// The tab's title is the window's `index:name`.
private fun tmuxWindowName(tab: TabSession): String =
    tab.sessionState.value.title?.substringAfter(':').orEmpty()

/** Asks for the active tmux window's new name while [TerminalOverlayState.renamingWindow] is set. */
@Composable
internal fun TmuxRenameWindowDialog(vm: TerminalViewModel, overlay: TerminalOverlayState) {
    val current = overlay.renamingWindow ?: return
    var name by remember(current) { mutableStateOf(current) }
    val close = { overlay.renamingWindow = null }
    val confirm = {
        if (name.isNotBlank()) vm.runTmuxAction(TmuxWindowAction.Rename(name))
        close()
    }
    AbyssDialog(
        title = stringResource(R.string.command_rename_window),
        confirmLabel = stringResource(R.string.action_rename),
        onConfirm = confirm,
        onDismiss = close,
    ) {
        AbyssTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.tmux_window_name),
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { confirm() }),
        )
    }
}

/** Asks before killing the active tmux window while [TerminalOverlayState.killingWindow] is set. */
@Composable
internal fun TmuxKillWindowDialog(vm: TerminalViewModel, overlay: TerminalOverlayState) {
    val name = overlay.killingWindow ?: return
    val close = { overlay.killingWindow = null }
    AbyssDialog(
        title = stringResource(R.string.tmux_kill_window_title, name),
        confirmLabel = stringResource(R.string.command_kill_window),
        onConfirm = {
            vm.runTmuxAction(TmuxWindowAction.KillWindow)
            close()
        },
        onDismiss = close,
    ) {
        AbyssText(
            stringResource(R.string.tmux_kill_window_body),
            style = AbyssTypography.current.body,
            color = AbyssColors.current.textSecondary,
        )
    }
}
