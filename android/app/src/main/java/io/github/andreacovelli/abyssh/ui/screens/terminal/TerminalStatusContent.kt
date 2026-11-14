package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.components.asString

import io.github.andreacovelli.abyssh.model.UiText

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * The frame around a status message: below the tab strip in strip mode, or padded on its
 * own otherwise.
 */
@Composable
private fun TerminalStatusFrame(
    tabStrip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (tabStrip == null) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart, content = content)
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        tabStrip()
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.TopStart,
            content = content,
        )
    }
}

/**
 * No session: the error with ways to recover, or in strip mode with every tab closed, a
 * button that opens a new one.
 */
@Composable
internal fun TerminalDisconnectedContent(
    vm: TerminalViewModel,
    error: UiText?,
    multiplexer: MultiplexerUiState,
    tabStrip: (@Composable () -> Unit)?,
    showNoSessions: Boolean,
    onNewConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEndSession: (() -> Unit)? = null,
) {
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    TerminalStatusFrame(tabStrip = tabStrip, modifier = modifier) {
        val preflightError = multiplexer.preflightErrorFor(activeTabId)
        val message = (preflightError ?: error)?.asString()
        when {
            message != null -> TerminalRecoveryActions(
                message = message,
                isMultiplexerPreflight = preflightError != null,
                dismissMultiplexerLabel = stringResource(
                    if (multiplexer.reconnectRecovery) R.string.action_dismiss else R.string.action_back,
                ),
                onRetryMultiplexer = vm.multiplexer::retryPending,
                onConnectWithoutMultiplexer = {
                    if (vm.multiplexer.connectPendingWithoutMultiplexer()) vm.files.selectPane(ConnectionTab.Terminal)
                },
                onBack = { vm.multiplexer.dismissRecovery(onBack) },
                onReconnect = vm::reconnect,
                onEndSession = onEndSession,
                // A tmux tab attaches to its tmux session again, which may still be running.
                reconnectLabel = stringResource(
                    if (activeTab?.spec?.usesTmuxControlMode == true) R.string.action_reattach else R.string.action_retry,
                ),
            )
            showNoSessions -> TerminalNoSessions(onNewConnection, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * What the terminal pane says in place of the terminal while its session opens: that it
 * is connecting, or, [centered], that the connection is up and its first screen is on the
 * way. [onCancel] stops the attempt, where that can be done.
 */
internal class TerminalWaiting(val message: String, val centered: Boolean, val onCancel: (() -> Unit)?)

/** [waiting]'s message, filling the room the terminal will take. */
@Composable
internal fun TerminalWaitingStatus(waiting: TerminalWaiting, stripMode: Boolean, modifier: Modifier = Modifier) {
    val centeredInStrip = waiting.centered && stripMode
    Box(
        modifier = modifier.fillMaxSize().then(if (centeredInStrip) Modifier else Modifier.padding(16.dp)),
        contentAlignment = when {
            centeredInStrip -> Alignment.Center
            waiting.centered -> Alignment.CenterStart
            else -> Alignment.TopStart
        },
    ) {
        Column(horizontalAlignment = if (centeredInStrip) Alignment.CenterHorizontally else Alignment.Start) {
            AbyssText(waiting.message, style = AbyssTypography.current.body)
            waiting.onCancel?.let { CancelConnectButton(it) }
        }
    }
}

/** Stops a connection attempt that is taking too long, such as on a network that went away. */
@Composable
private fun CancelConnectButton(onCancel: () -> Unit) {
    val colors = AbyssColors.current
    Spacer(Modifier.height(16.dp))
    AbyssButton(
        onClick = onCancel,
        variant = AbyssButtonVariant.Ghost,
        borderColor = colors.textMuted,
    ) {
        AbyssText(stringResource(R.string.action_cancel), style = AbyssTypography.current.label, color = colors.textMuted)
    }
}

/**
 * The host a status message names: the multiplexer check's host while it runs, then the
 * active tab in strip mode or the route's tab otherwise.
 */
@Composable
internal fun connectingLabel(
    multiplexer: MultiplexerUiState,
    stripMode: Boolean,
    activeTab: TabSession?,
    routeTab: TabSession?,
): String {
    if (multiplexer.preflightLoading || multiplexer.sessionChoice != null) {
        return "${multiplexer.preflightHostLabel ?: stringResource(R.string.terminal_host_fallback)}..."
    }
    val name = if (stripMode) activeTab?.spec?.tabLabel else routeTab?.spec?.host
    return name?.let { "$it..." } ?: "..."
}

@Composable
internal fun preparingMessage(
    reconnecting: Boolean,
    stripMode: Boolean,
    activeTab: TabSession?,
    routeTab: TabSession?,
): String {
    if (!reconnecting) return stringResource(R.string.terminal_preparing)
    val name = if (stripMode) activeTab?.spec?.tabLabel else routeTab?.spec?.host
    return name?.let { stringResource(R.string.terminal_reconnecting_to, it) }
        ?: stringResource(R.string.terminal_reconnecting_ellipsis)
}
