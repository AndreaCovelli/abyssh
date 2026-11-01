package io.github.andreacovelli.abyssh.ui.screens.serverlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.repository.HostRepository
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.OpenTab
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.service.terminal.sessionLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ServerListViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sessionRepository = TerminalSessionRepository.getInstance(application)

    private val searchQuery = MutableStateFlow("")

    val hosts: StateFlow<List<HostProfile>> = hostRepository.observeAll()
        .combine(searchQuery) { hosts, query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@combine hosts
            hosts.filter { host ->
                host.name.contains(trimmed, ignoreCase = true) ||
                    host.host.contains(trimmed, ignoreCase = true) ||
                    host.username.contains(trimmed, ignoreCase = true)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /** Hosts with at least one connected tab. */
    val connectedHostIds: StateFlow<Set<Long>> = sessionRepository.connectedHostIds

    /** The tabs open per host and on the local shell, with each tab's terminal title. */
    val openSessions: StateFlow<OpenSessions> = sessionRepository.tabs
        .flatMapLatest { tabs ->
            if (tabs.isEmpty()) {
                flowOf(OpenSessions())
            } else {
                combine(tabs.map { tab -> tab.sessionState.map { it.title }.distinctUntilChanged() }) { titles ->
                    openSessionsOf(tabs, titles.toList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenSessions())

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    /** Retries the host's failed tabs before the terminal screen shows them again. */
    fun prepareConnect(id: Long) {
        sessionRepository.reconnectIdleTabsForHost(id)
    }

    /** Closes every tab connected to the host. */
    fun disconnectHost(id: Long) {
        closeTabsWhere { it.spec.hostId == id }
    }

    /**
     * Closes one session of the host, leaving the others open. A tmux control-mode
     * session detaches with all its windows' tabs, and keeps running on the server.
     */
    fun closeSession(tabId: String) {
        sessionRepository.disconnectTabs(listOf(tabId))
    }

    /** Closes every local shell tab. */
    fun disconnectLocalShell() {
        closeTabsWhere { it.spec.transport == Transport.LocalShell }
    }

    private fun closeTabsWhere(predicate: (TabSession) -> Boolean) {
        sessionRepository.disconnectTabs(sessionRepository.tabs.value.filter(predicate).map { it.id })
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            val host = hostRepository.getById(id) ?: return@launch
            hostRepository.delete(host)
        }
    }

    val search: StateFlow<String> = searchQuery.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "",
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ServerListViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ServerListViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

/**
 * One session as the server list shows it: a tab, with its numbered label and terminal
 * title, or a tmux control-mode session, whose [tmuxWindows] tabs share one row that
 * [tabId] stands for.
 */
data class OpenSession(val tabId: String, val label: String, val title: String?, val tmuxWindows: Int = 0)

/** The open tabs shown on the server list. */
data class OpenSessions(
    val localShellTabs: Int = 0,
    val sessionsByHost: Map<Long, List<OpenSession>> = emptyMap(),
) {
    val hasLocalShell: Boolean get() = localShellTabs > 0

    fun sessionsFor(hostId: Long): List<OpenSession> = sessionsByHost[hostId].orEmpty()

    fun tabsFor(hostId: Long): Int = sessionsFor(hostId).size
}

/**
 * Groups [tabs] by saved host, with one row for each tmux control-mode session however
 * many windows it has; [titles] holds each tab's terminal title, in the same order.
 */
internal fun openSessionsOf(tabs: List<OpenTab>, titles: List<String?>): OpenSessions {
    val sessionsByHost = tabs.withIndex()
        .mapNotNull { indexed -> indexed.value.spec.hostId?.let { it to indexed } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, hostTabs) ->
            // LinkedHashMap keeps the rows in the order of each session's first tab.
            hostTabs.groupBy { (_, tab) -> tab.tmuxSessionKey() ?: tab.id }.values.map { group ->
                val (i, tab) = group.first()
                val tmuxSession = tab.tmuxSessionKey()?.second
                if (tmuxSession != null) {
                    OpenSession(tab.id, "${tab.spec.tabLabel} · $tmuxSession", title = null, tmuxWindows = group.size)
                } else {
                    OpenSession(tab.id, tab.sessionLabel(tabs), titles.getOrNull(i)?.takeIf(String::isNotBlank))
                }
            }
        }
    return OpenSessions(
        localShellTabs = tabs.count { it.spec.transport == Transport.LocalShell },
        sessionsByHost = sessionsByHost,
    )
}

/** The host and tmux session that a tmux control-mode tab shows a window of, or null for any other tab. */
private fun OpenTab.tmuxSessionKey(): Pair<String, String>? {
    val name = spec.multiplexerSessionName?.takeIf { spec.usesTmuxControlMode && it.isNotBlank() } ?: return null
    return spec.sessionKey to name
}
