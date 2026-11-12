package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
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
        if (tab.sessionState.value.status.isLive) pendingTabId = tab.id else vm.endTab(tab.id)
    }

    /**
     * Closes tab [id] from a tab list at once, as tabs do. A tmux control-mode tab detaches
     * its session, which closes the session's other tabs and leaves its windows running.
     */
    fun requestClose(id: String) {
        vm.endTab(id)
    }

    fun confirm() {
        pendingTabId?.let(vm::endTab)
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
        confirmLabel = stringResource(endTabLabel(tab)),
        onConfirm = endSession::confirm,
        onDismiss = endSession::dismiss,
    ) {
        AbyssText(
            stringResource(endSessionBody(tab)),
            style = AbyssTypography.current.body,
            color = AbyssColors.current.textSecondary,
        )
    }
}

/** The action that ending live [tab] takes: it detaches from tmux, disconnects, or ends the local shell. */
@StringRes
internal fun endTabLabel(tab: TabSession): Int = when {
    tab.engine.tmuxControl != null -> R.string.action_detach
    tab.spec.transport == Transport.LocalShell -> R.string.terminal_end_shell
    else -> R.string.terminal_disconnect
}

/** What ending [tab] does: a tmux control-mode tab detaches, and any other closes its session. */
@StringRes
private fun endSessionBody(tab: TabSession): Int =
    if (tab.engine.tmuxControl != null) R.string.terminal_end_session_body_tmux_detach else R.string.terminal_end_session_body
