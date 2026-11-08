package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TmuxWindowAction
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
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
    BuiltinCommand.RenameWindow to null,
    BuiltinCommand.PreviousWindow to TmuxWindowAction.PreviousWindow,
    BuiltinCommand.NextWindow to TmuxWindowAction.NextWindow,
    BuiltinCommand.Detach to TmuxWindowAction.Detach,
)

/** The shortcut handlers for the tmux window commands, for a tab that shows a tmux window. */
internal fun tmuxCommandHandlers(vm: TerminalViewModel, overlay: TerminalOverlayState): Map<BuiltinCommand, () -> Unit> =
    TmuxWindowCommands.associate { (command, action) -> command to { runTmuxCommand(vm, overlay, action) } }

/** Runs [action] on the active tab's tmux window, or asks for a new name when [action] is null. */
internal fun runTmuxCommand(vm: TerminalViewModel, overlay: TerminalOverlayState, action: TmuxWindowAction?) {
    if (action != null) {
        vm.runTmuxAction(action)
    } else {
        overlay.renamingWindow = vm.activeTab.value?.let(::tmuxWindowName).orEmpty()
    }
}

// The tab's title is the window's `index:name`.
private fun tmuxWindowName(tab: TabSession): String =
    tab.sessionState.value.title?.substringAfter(':').orEmpty()

/** The tmux window commands, shown in the tab switchers when the active tab shows a tmux window. */
@Composable
internal fun TmuxWindowActionsPanel(vm: TerminalViewModel, overlay: TerminalOverlayState, onDismiss: () -> Unit) {
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    if (activeTab?.spec?.usesTmuxControlMode != true) return
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        AbyssText(stringResource(R.string.tmux_window_actions), style = typography.body, color = colors.textSecondary)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TmuxWindowCommands.forEach { (command, action) ->
                AbyssButton(
                    onClick = {
                        onDismiss()
                        runTmuxCommand(vm, overlay, action)
                    },
                    variant = AbyssButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    AbyssText(stringResource(command.label), style = typography.labelSmall, color = colors.accent)
                }
            }
        }
    }
}

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
