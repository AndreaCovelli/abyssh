package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Immutable
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec

/** The terminal route: one saved host's tabs, or the local shell's. */
@Immutable
internal data class TerminalRoute(
    val hostId: Long?,
    val localShell: Boolean,
) {
    fun owns(spec: TabSpec): Boolean = when {
        localShell -> spec.hostId == null && spec.transport == Transport.LocalShell
        hostId != null -> spec.hostId == hostId
        else -> false
    }

    fun tabsIn(tabs: List<TabSession>): List<TabSession> = tabs.filter { owns(it.spec) }

    companion object {
        val localShellSpec = TabSpec(
            hostId = null,
            displayName = "local shell",
            host = "localhost",
            username = "android",
            authMethod = AuthMethod.None,
            transport = Transport.LocalShell,
        )
    }
}

/** The name to show for this tab's host: its saved name, or the host address. */
internal fun TabSpec.hostLabel(): String? = displayName.takeIf { it.isNotBlank() } ?: host.takeIf { it.isNotBlank() }
