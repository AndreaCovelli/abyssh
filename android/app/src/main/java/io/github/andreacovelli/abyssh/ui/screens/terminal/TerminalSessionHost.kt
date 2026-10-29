package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings

/** What the connected session needs from the screen around it. */
@Stable
internal class TerminalSessionHost(
    val settings: TerminalUiSettings,
    val fontSize: MutableFloatState,
    val overlay: TerminalOverlayState,
    val abysshKeys: AbysshKeyBindings,
    val routeTabs: List<TabSession>,
    val tabStrip: (@Composable () -> Unit)?,
    val onNewConnection: () -> Unit,
    val onEndSession: () -> Unit,
)
