package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.model.toUiText

import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.R

import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MultiplexerUiState(
    val preflightError: UiText? = null,
    val preflightLoading: Boolean = false,
    val preflightHostLabel: String? = null,
    val duplicateLoading: Boolean = false,
    val sessions: List<RemoteMultiplexerSession> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: UiText? = null,
    val sessionsSourceTabId: String? = null,
    val sessionsSourceHostId: Long? = null,
    val reconnectRecovery: Boolean = false,
)

/**
 * Tabs that run inside a remote multiplexer such as tmux. Opening or reconnecting one
 * first resolves the remote session name over SSH, which can fail or be superseded, so
 * each attempt carries a generation and only the latest one may open a tab.
 */
// Preflight errors come from native and transport code as many exception types; the
// broad catch shows them in the recovery panel.
@Suppress("TooGenericExceptionCaught")
class TerminalMultiplexer internal constructor(
    private val sessionRepository: TerminalSessionRepository,
    private val scope: CoroutineScope,
) {
    private sealed class PendingAction(open val spec: TabSpec) {
        data class Open(override val spec: TabSpec) : PendingAction(spec)
        data class Reconnect(val tabId: String, override val spec: TabSpec) : PendingAction(spec)
    }

    private val _state = MutableStateFlow(MultiplexerUiState())
    val state: StateFlow<MultiplexerUiState> = _state.asStateFlow()
    private var pendingAction: PendingAction? = null
    private var actionGeneration = 0L
    private var sessionListGeneration = 0L
    private var duplicateInFlight = false

    /** Opens another tab on the host of [spec], in a new multiplexer session. */
    fun duplicate(spec: TabSpec) {
        if (duplicateInFlight) return
        duplicateInFlight = true
        _state.update { it.copy(duplicateLoading = true, preflightError = null) }
        val duplicateSpec = spec.withNewSession()
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { sessionRepository.resolveMultiplexerSessionName(duplicateSpec) }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { name -> sessionRepository.openTab(duplicateSpec.copy(multiplexerSessionName = name)) },
                        onFailure = { error ->
                            _state.update {
                                it.copy(preflightError = error.toUiText(UiText.Resource(R.string.multiplexer_create_failed)))
                            }
                        },
                    )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    duplicateInFlight = false
                    _state.update { it.copy(duplicateLoading = false) }
                }
            }
        }
    }

    fun open(spec: TabSpec) {
        begin(PendingAction.Open(spec))
    }

    fun reconnect(tab: TabSession) {
        begin(PendingAction.Reconnect(tab.id, tab.spec))
    }

    fun retryPending() {
        val action = pendingAction ?: return
        begin(action)
    }

    /** Gives up on the multiplexer and opens or reconnects the pending tab as a plain shell. */
    fun connectPendingWithoutMultiplexer(): Boolean {
        val action = pendingAction ?: return false
        val plainSpec = action.spec.copy(
            multiplexer = null,
            multiplexerSessionName = null,
            multiplexerCreateIfMissing = true,
        )
        clearPending()
        return when (action) {
            is PendingAction.Open -> {
                sessionRepository.openTab(plainSpec)
                true
            }
            is PendingAction.Reconnect -> reconnectTabWithSpec(action.tabId, plainSpec)
        }
    }

    /** Cancels the pending action; [onBack] runs when it was opening a new tab. */
    fun dismissRecovery(onBack: () -> Unit) {
        val shouldNavigateBack = pendingAction is PendingAction.Open
        clearPending()
        if (shouldNavigateBack) onBack()
    }

    private fun clearPending() {
        _state.value = MultiplexerUiState()
        pendingAction = null
        actionGeneration += 1
    }

    private fun begin(action: PendingAction) {
        val prepared = when (action) {
            is PendingAction.Open -> action.copy(spec = action.spec.withDefaultMultiplexer())
            is PendingAction.Reconnect -> action.copy(spec = action.spec.withDefaultMultiplexer())
        }
        if (pendingAction == prepared && _state.value.preflightLoading) {
            // Keep the original generation alive while its host-key prompt is awaiting a decision.
            return
        }
        val generation = ++actionGeneration
        pendingAction = prepared
        val spec = prepared.spec
        val reconnectRecovery = prepared is PendingAction.Reconnect
        val unsupported = unsupportedReason(spec)
        if (unsupported != null) {
            _state.value = MultiplexerUiState(preflightError = unsupported, reconnectRecovery = reconnectRecovery)
            return
        }
        _state.value = MultiplexerUiState(
            preflightLoading = true,
            preflightHostLabel = spec.tabLabel,
            reconnectRecovery = reconnectRecovery,
        )
        scope.launch(Dispatchers.IO) {
            val result =
                try {
                    Result.success(
                        sessionRepository.resolveMultiplexerSessionName(
                            spec = spec,
                            reuseDetachedAbysshSession =
                                prepared is PendingAction.Open && spec.multiplexerSessionName.isNullOrBlank(),
                        ),
                    )
                } catch (_: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) { reportStopped(generation, prepared) }
                    return@launch
                } catch (error: Exception) {
                    Result.failure(error)
                }
            withContext(Dispatchers.Main) {
                if (!isCurrent(generation, prepared)) {
                    reportStopped(generation, prepared)
                    return@withContext
                }
                result.fold(
                    onSuccess = { sessionName -> complete(prepared, sessionName) },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                preflightLoading = false,
                                preflightHostLabel = null,
                                preflightError = error.toUiText(UiText.Resource(R.string.multiplexer_prepare_failed)),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun unsupportedReason(spec: TabSpec): UiText? {
        val multiplexer = spec.multiplexer ?: MultiplexerRegistry.defaultType
        return when {
            spec.transport == Transport.Mosh ->
                UiText.Resource(R.string.multiplexer_unsupported_transport, multiplexer.label, Transport.Mosh.name)
            !multiplexer.runtimeSupported -> UiText.Resource(R.string.multiplexer_unsupported, multiplexer.label)
            else -> null
        }
    }

    private fun isCurrent(generation: Long, action: PendingAction): Boolean =
        actionGeneration == generation && pendingAction == action

    private fun complete(action: PendingAction, sessionName: String) {
        val nextSpec = action.spec.copy(
            multiplexer = action.spec.multiplexer ?: MultiplexerRegistry.defaultType,
            multiplexerSessionName = sessionName,
        )
        pendingAction = null
        _state.value = MultiplexerUiState()
        when (action) {
            is PendingAction.Open -> sessionRepository.openTab(nextSpec)
            is PendingAction.Reconnect -> {
                if (!reconnectTabWithSpec(action.tabId, nextSpec)) {
                    _state.value = MultiplexerUiState(
                        preflightError = UiText.Resource(R.string.terminal_session_unavailable),
                        reconnectRecovery = true,
                    )
                }
            }
        }
    }

    private fun reportStopped(generation: Long, action: PendingAction) {
        when {
            isCurrent(generation, action) -> _state.update {
                it.copy(
                    preflightLoading = false,
                    preflightHostLabel = null,
                    preflightError = UiText.Resource(R.string.terminal_preparation_cancelled),
                )
            }
            pendingAction == null -> _state.value = MultiplexerUiState(
                preflightError = UiText.Resource(R.string.terminal_preparation_superseded),
                reconnectRecovery = action is PendingAction.Reconnect,
            )
        }
    }

    private fun reconnectTabWithSpec(tabId: String, spec: TabSpec): Boolean {
        val tab = sessionRepository.tabs.value.firstOrNull { it.id == tabId } ?: return false
        tab.spec = spec
        sessionRepository.reconnectTab(tab)
        return true
    }

    /** Loads the sessions on the active tab's host for the session panel. */
    fun listSessionsForActiveHost() {
        val tab = sessionRepository.activeTab.value ?: return
        val requestGeneration = ++sessionListGeneration
        val sourceTabId = tab.id
        val sourceHostId = tab.spec.hostId
        val spec = tab.spec.withDefaultMultiplexer()
        _state.update {
            it.copy(
                sessions = emptyList(),
                sessionsLoading = true,
                sessionsError = null,
                sessionsSourceTabId = sourceTabId,
                sessionsSourceHostId = sourceHostId,
            )
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.listMultiplexerSessions(spec) }
            withContext(Dispatchers.Main) {
                val stale = requestGeneration != sessionListGeneration ||
                    sessionRepository.activeTab.value?.id != sourceTabId
                if (stale) return@withContext
                _state.update {
                    it.copy(
                        sessions = result.getOrDefault(emptyList()),
                        sessionsLoading = false,
                        sessionsError = result.exceptionOrNull()?.toUiText(UiText.Resource(R.string.multiplexer_list_failed)),
                        sessionsSourceTabId = sourceTabId,
                        sessionsSourceHostId = sourceHostId,
                    )
                }
            }
        }
    }

    /** Opens a tab in a new session on the active tab's host. */
    fun createNextSession() {
        val tab = sessionRepository.activeTab.value ?: return
        val nextSpec = tab.spec.withNewSession()
        scope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.resolveMultiplexerSessionName(nextSpec) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        sessionRepository.openTab(nextSpec.copy(multiplexerSessionName = name))
                        listSessionsForActiveHost()
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(sessionsError = error.toUiText(UiText.Resource(R.string.multiplexer_create_failed)))
                        }
                    },
                )
            }
        }
    }

    /**
     * Attaches the active tab to session [name] from the panel, as long as the panel's list
     * still belongs to the active tab or its host.
     */
    fun switchToSession(name: String, sourceTabId: String?) {
        val activeTab = sessionRepository.activeTab.value
        val state = _state.value
        val activeHostId = activeTab?.spec?.hostId
        val listMatchesTab = sourceTabId != null &&
            state.sessionsSourceTabId == sourceTabId &&
            activeTab?.id == sourceTabId
        val listMatchesHost = activeHostId != null && state.sessionsSourceHostId == activeHostId
        if (activeTab == null || (!listMatchesTab && !listMatchesHost)) {
            _state.value = state.copy(
                sessions = emptyList(),
                sessionsLoading = false,
                sessionsError = UiText.Resource(R.string.multiplexer_sessions_stale),
                sessionsSourceTabId = activeTab?.id,
                sessionsSourceHostId = activeHostId,
            )
            return
        }
        sessionRepository.switchActiveMultiplexerSession(name)
    }
}

private fun TabSpec.withDefaultMultiplexer(): TabSpec =
    copy(multiplexer = multiplexer ?: MultiplexerRegistry.defaultType)

private fun TabSpec.withNewSession(): TabSpec = copy(
    multiplexer = multiplexer ?: MultiplexerRegistry.defaultType,
    multiplexerSessionName = null,
    multiplexerCreateIfMissing = true,
)
