package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

@Composable
internal fun terminalTabEndpointLabel(tab: TabSession): String =
    if (tab.spec.transport == Transport.LocalShell) {
        stringResource(R.string.tab_local_endpoint)
    } else {
        "${tab.spec.username}@${tab.spec.host}:${tab.spec.port}"
    }

/** The multiplexer session a tab shows, such as `tmux: abyssh-1`, or null when it uses none. */
internal fun terminalTabMultiplexerLabel(tab: TabSession): String? {
    val spec = tab.spec
    if (!spec.usesRuntimeMultiplexer) return null
    val name = spec.multiplexerSessionName?.takeIf { it.isNotBlank() } ?: return null
    return "${(spec.multiplexer ?: MultiplexerRegistry.defaultType).label}: $name"
}

/**
 * Outlines the row that keyboard or pointer focus is on. An outline rather than a fill,
 * so it doesn't read as the active tab, which the green dot marks.
 */
@Composable
internal fun Modifier.tabFocusOutline(focused: Boolean): Modifier =
    if (focused) border(1.dp, AbyssColors.current.accent.copy(alpha = 0.6f), MaterialTheme.shapes.medium) else this
