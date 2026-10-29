package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.TabSession

@Composable
internal fun terminalTabEndpointLabel(tab: TabSession): String =
    if (tab.spec.transport == Transport.LocalShell) {
        stringResource(R.string.tab_local_endpoint)
    } else {
        "${tab.spec.username}@${tab.spec.host}:${tab.spec.port}"
    }
