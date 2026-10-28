package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.repository.HostRepository
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.data.repository.SshKeyRepository
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.HostKeyPrompt
import io.github.andreacovelli.abyssh.service.terminal.SessionState
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The terminal screen's tabs. Search, the file browser, multiplexer sessions, and input
 * live in the collaborators [search], [files], [multiplexer], and [input].
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val database = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(database.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(database.sshKeyDao())
    private val settingsRepository = SettingsRepository.getInstance(application)

    val search = TerminalSearchController(sessionRepository, viewModelScope)
    val files = TerminalFileBrowser(sessionRepository, viewModelScope)
    val multiplexer = TerminalMultiplexer(sessionRepository, viewModelScope)
    val input = TerminalInput(sessionRepository)

    val tabs: StateFlow<List<TabSession>> = sessionRepository.tabs
    val activeTabId: StateFlow<String?> = sessionRepository.activeTabId
    val activeTab: StateFlow<TabSession?> = sessionRepository.activeTab
    val sessionState: StateFlow<SessionState> = sessionRepository.sessionState
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt
    val hosts: StateFlow<List<HostProfile>> =
        hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        sessionRepository.attachClient()
        // Search matches and links belong to one terminal, so switching tabs drops them.
        viewModelScope.launch {
            activeTabId.collect { search.reset() }
        }
    }

    /** Selects the active tab if it's on [hostId], else that host's newest tab. */
    fun selectTabForHost(hostId: Long?): TabSession? = selectAmong(sessionRepository.tabsForHost(hostId))

    /** Selects the active local shell tab, else the newest one. */
    fun selectLocalShellTab(): TabSession? = selectAmong(
        sessionRepository.tabs.value.filter {
            it.spec.hostId == null && it.spec.transport == Transport.LocalShell
        },
    )

    private fun selectAmong(candidates: List<TabSession>): TabSession? {
        if (candidates.isEmpty()) return null
        val activeId = sessionRepository.activeTabId.value
        val target = candidates.firstOrNull { it.id == activeId } ?: candidates.last()
        sessionRepository.selectTab(target.id)
        return target
    }

    fun openTab(spec: TabSpec): TabSession = sessionRepository.openTab(spec)

    data class PreparedTabOpen(
        val spec: TabSpec,
        val requiresVerification: Boolean,
    )

    suspend fun prepareTabOpenForHost(hostId: Long): PreparedTabOpen? {
        val host = hostRepository.getById(hostId) ?: return null
        val key = host.keyId?.let { sshKeyRepository.getById(it) }
        val spec = TabSpec.fromHostProfile(
            host = host,
            publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
            privateKeyPem = key?.privateKeyPem.orEmpty(),
        )
        val requiresVerification =
            settingsRepository.requireAuthOnConnect.value || host.requireAuthOnConnect
        return PreparedTabOpen(spec = spec, requiresVerification = requiresVerification)
    }

    /**
     * Opens another tab like [tabId], or like the active tab. A multiplexer tab gets a new
     * session, opened in the background, so this returns null for it.
     */
    fun duplicateTab(tabId: String? = null): TabSession? {
        val tab = if (tabId == null) {
            sessionRepository.activeTab.value
        } else {
            sessionRepository.tabs.value.firstOrNull { it.id == tabId }
        } ?: return null
        if (tab.spec.usesRuntimeMultiplexer) {
            multiplexer.duplicate(tab.spec)
            return null
        }
        return openTab(tab.spec)
    }

    fun selectTab(id: String) {
        sessionRepository.selectTab(id)
    }

    fun closeTab(id: String) {
        files.forget(id)
        sessionRepository.closeTab(id)
    }

    fun reconnect() {
        val tab = sessionRepository.activeTab.value ?: return
        if (tab.spec.usesRuntimeMultiplexer) {
            multiplexer.reconnect(tab)
        } else {
            sessionRepository.reconnectActive()
        }
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    override fun onCleared() {
        sessionRepository.detachClient()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return TerminalViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
