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
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** How many tabs are open per host and on the local shell. */
    val openSessions: StateFlow<OpenSessions> = sessionRepository.tabs
        .map { tabs ->
            OpenSessions(
                localShellTabs = tabs.count { it.spec.transport == Transport.LocalShell },
                tabsByHost = tabs.mapNotNull { it.spec.hostId }.groupingBy { it }.eachCount(),
            )
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

    /** Closes every local shell tab. */
    fun disconnectLocalShell() {
        closeTabsWhere { it.spec.transport == Transport.LocalShell }
    }

    private fun closeTabsWhere(predicate: (TabSession) -> Boolean) {
        sessionRepository.tabs.value.filter(predicate).map { it.id }.forEach(sessionRepository::closeTab)
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

/** Open tab counts shown on the server list. */
data class OpenSessions(
    val localShellTabs: Int = 0,
    val tabsByHost: Map<Long, Int> = emptyMap(),
) {
    val hasLocalShell: Boolean get() = localShellTabs > 0

    fun tabsFor(hostId: Long): Int = tabsByHost[hostId] ?: 0
}
