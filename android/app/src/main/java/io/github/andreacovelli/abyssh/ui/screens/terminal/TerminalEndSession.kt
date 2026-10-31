package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.sessionLabel
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Ends the active tab from the terminal screen, leaving the host's other tabs open. A
 * live session asks first; one that is down or failed closes at once.
 */
@Stable
internal class TerminalEndSession(private val vm: TerminalViewModel) {
    /** The tab waiting for the user to confirm, or null when no prompt shows. */
    var pendingTabId by mutableStateOf<String?>(null)
        private set

    fun request() {
        val tab = vm.activeTab.value ?: return
        if (tab.sessionState.value.status.isLive) pendingTabId = tab.id else vm.closeTab(tab.id)
    }

    fun confirm() {
        pendingTabId?.let(vm::closeTab)
        pendingTabId = null
    }

    fun dismiss() {
        pendingTabId = null
    }
}

@Composable
internal fun rememberTerminalEndSession(vm: TerminalViewModel): TerminalEndSession = remember(vm) { TerminalEndSession(vm) }

@Composable
internal fun TerminalEndSessionDialog(endSession: TerminalEndSession, tabs: List<TabSession>) {
    val tabId = endSession.pendingTabId ?: return
    val tab = tabs.firstOrNull { it.id == tabId }
    // The tab closed some other way while the prompt was up.
    LaunchedEffect(tab == null) {
        if (tab == null) endSession.dismiss()
    }
    if (tab == null) return
    AbyssDialog(
        title = stringResource(R.string.terminal_end_session_title, tab.sessionLabel(tabs)),
        confirmLabel = stringResource(R.string.terminal_end_session),
        onConfirm = endSession::confirm,
        onDismiss = endSession::dismiss,
    ) {
        AbyssText(
            stringResource(endSessionBody(tab, tabs)),
            style = AbyssTypography.current.body,
            color = AbyssColors.current.textSecondary,
        )
    }
}

/** What ending [tab] does: a tmux window's tab ends the window, or detaches when it is the session's last tab. */
private fun endSessionBody(tab: TabSession, tabs: List<TabSession>): Int {
    val control = tab.engine.tmuxControl ?: return R.string.terminal_end_session_body
    val lastTab = tabs.none { it !== tab && it.engine.tmuxControl === control }
    return if (lastTab) R.string.terminal_end_session_body_tmux_detach else R.string.terminal_end_session_body_tmux_window
}
