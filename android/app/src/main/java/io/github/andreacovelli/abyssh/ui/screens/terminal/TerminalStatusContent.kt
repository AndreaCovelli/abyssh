package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * The frame around a status message: below the tab strip in strip mode, or padded on its
 * own otherwise. [centered] puts the content in the middle instead of the top start.
 */
@Composable
private fun TerminalStatusFrame(
    tabStrip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (tabStrip == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp),
            contentAlignment = if (centered) Alignment.CenterStart else Alignment.TopStart,
            content = content,
        )
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        tabStrip()
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().then(if (centered) Modifier else Modifier.padding(16.dp)),
            contentAlignment = if (centered) Alignment.Center else Alignment.TopStart,
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
    error: String?,
    multiplexer: MultiplexerUiState,
    tabStrip: (@Composable () -> Unit)?,
    showNoSessions: Boolean,
    onNewConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TerminalStatusFrame(tabStrip = tabStrip, modifier = modifier) {
        val message = multiplexer.preflightError ?: error
        when {
            message != null -> TerminalRecoveryActions(
                message = message,
                isMultiplexerPreflight = multiplexer.preflightError != null,
                dismissMultiplexerLabel = stringResource(
                    if (multiplexer.reconnectRecovery) R.string.action_dismiss else R.string.action_back,
                ),
                onRetryMultiplexer = vm.multiplexer::retryPending,
                onConnectWithoutMultiplexer = {
                    if (vm.multiplexer.connectPendingWithoutMultiplexer()) vm.files.selectPane(ConnectionTab.Terminal)
                },
                onBack = { vm.multiplexer.dismissRecovery(onBack) },
                onReconnect = vm::reconnect,
            )
            showNoSessions -> TerminalNoSessions(onNewConnection, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
internal fun TerminalConnectingContent(
    label: String,
    tabStrip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TerminalStatusFrame(tabStrip = tabStrip, modifier = modifier) {
        AbyssText(stringResource(R.string.terminal_connecting_to, label), style = AbyssTypography.current.body)
    }
}

/** Connected or reconnecting, before the first screen of output arrives. */
@Composable
internal fun TerminalPreparingContent(
    message: String,
    tabStrip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TerminalStatusFrame(tabStrip = tabStrip, modifier = modifier, centered = true) {
        AbyssText(message, style = AbyssTypography.current.body)
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
    if (multiplexer.preflightLoading) {
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
