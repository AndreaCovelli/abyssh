package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.model.toUiText

import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.R

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.RemoteMultiplexerSessions
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
    val sessionChoice: MultiplexerSessionChoice? = null,
)

/**
 * The sessions a tab can attach to on a host that asks which one to use when it
 * connects. [openInTab] names the sessions an open tab already shows.
 */
data class MultiplexerSessionChoice(
    val type: MultiplexerType,
    val sessions: List<RemoteMultiplexerSession>,
    val openInTab: Set<String>,
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
        /**
         * [leaveOnCancel] leaves the screen when the user cancels, as for the first tab a
         * route opens. [newSession] skips both the session picker and reusing a detached session.
         */
        data class Open(
            override val spec: TabSpec,
            val leaveOnCancel: Boolean = true,
            val newSession: Boolean = false,
        ) : PendingAction(spec) {
            /** Whether this lists the host's sessions for the user to pick from instead of picking one. */
            val choosesSession: Boolean
                get() = !newSession && spec.chooseSessionOnConnect && spec.multiplexerSessionName.isNullOrBlank()
        }
        data class Reconnect(val tabId: String, override val spec: TabSpec) : PendingAction(spec)
    }

    private val _state = MutableStateFlow(MultiplexerUiState())
    val state: StateFlow<MultiplexerUiState> = _state.asStateFlow()
    private var pendingAction: PendingAction? = null
    private var actionGeneration = 0L
    private var sessionListGeneration = 0L
    private var duplicateInFlight = false

    /** Opens another tab on the host of [spec], in a new multiplexer session or a new tmux control-mode window. */
    fun duplicate(spec: TabSpec) {
        if (duplicateInFlight) return
        // A host in tmux control mode gets a new window in its session, which opens as a tab.
        if (sessionRepository.openTmuxWindow(spec)) return
        // A host that asks which session to use shows its sessions instead of creating one.
        if (spec.chooseSessionOnConnect) {
            open(spec.withNewSession(), leaveOnCancel = false)
            return
        }
        duplicateInFlight = true
        _state.update { it.copy(duplicateLoading = true, preflightError = null) }
        val duplicateSpec = spec.withNewSession()
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { sessionRepository.multiplexerSessions.resolveName(duplicateSpec) }
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

    /** Opens a tab on [spec]; [leaveOnCancel] leaves the screen when the user cancels. */
    fun open(spec: TabSpec, leaveOnCancel: Boolean = true) {
        begin(PendingAction.Open(spec, leaveOnCancel = leaveOnCancel))
    }

    /**
     * Attaches the tab waiting in the session picker to session [name], or selects the
     * tab that already shows it, so one session never gets two sets of tabs.
     */
    fun chooseSession(name: String) {
        val action = pendingAction as? PendingAction.Open ?: return
        if (_state.value.sessionChoice == null) return
        val spec = action.spec.withDefaultMultiplexer()
        val openTab = sessionRepository.multiplexerSessions.tabsShowing(spec, name).firstOrNull()
        if (openTab != null) {
            clearPending()
            sessionRepository.selectTab(openTab.id)
            return
        }
        complete(action, name, createIfMissing = false)
    }

    /** Opens the tab waiting in the session picker in a new session. */
    fun chooseNewSession() {
        val action = pendingAction as? PendingAction.Open ?: return
        if (_state.value.sessionChoice == null) return
        begin(action.copy(newSession = true))
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

    /** Cancels the pending action; [onBack] runs when it was opening a tab that leaves on cancel. */
    fun dismissRecovery(onBack: () -> Unit) {
        val shouldNavigateBack = (pendingAction as? PendingAction.Open)?.leaveOnCancel == true
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
        if (prepared is PendingAction.Open && prepared.choosesSession) {
            listChoices(generation, prepared)
        } else {
            resolve(generation, prepared)
        }
    }


    /** Lists the sessions on the pending tab's host and shows them in the session picker. */
    private fun listChoices(generation: Long, action: PendingAction.Open) {
        // On the main thread: the listing picks a tmux connection from the open tabs, and
        // moves to the IO thread itself when it opens a new one.
        scope.launch(Dispatchers.Main) {
            val result =
                try {
                    Result.success(sessionRepository.multiplexerSessions.list(action.spec))
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { reportStopped(generation, action) }
                    return@launch
                } catch (error: Exception) {
                    Result.failure(error)
                }
            if (!isCurrent(generation, action)) {
                reportStopped(generation, action)
                return@launch
            }
            result.fold(
                onSuccess = { sessions ->
                    _state.value = MultiplexerUiState(
                        preflightHostLabel = action.spec.tabLabel,
                        sessionChoice = sessionRepository.multiplexerSessions.choiceFor(action.spec, sessions),
                    )
                },
                onFailure = { error -> _state.update { it.failed(error, UiText.Resource(R.string.multiplexer_list_failed)) } },
            )
        }
    }

    /** Picks the pending tab's session over SSH, then opens or reconnects the tab in it. */
    private fun resolve(generation: Long, prepared: PendingAction) {
        val spec = prepared.spec
        scope.launch(Dispatchers.IO) {
            val result =
                try {
                    Result.success(
                        sessionRepository.multiplexerSessions.resolveName(
                            spec = spec,
                            reuseDetachedAbysshSession =
                                prepared is PendingAction.Open &&
                                    !prepared.newSession &&
                                    spec.multiplexerSessionName.isNullOrBlank(),
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
                        _state.update { it.failed(error, UiText.Resource(R.string.multiplexer_prepare_failed)) }
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

    private fun complete(
        action: PendingAction,
        sessionName: String,
        createIfMissing: Boolean = action.spec.multiplexerCreateIfMissing,
    ) {
        val nextSpec = action.spec.copy(
            multiplexer = action.spec.multiplexer ?: MultiplexerRegistry.defaultType,
            multiplexerSessionName = sessionName,
            multiplexerCreateIfMissing = createIfMissing,
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

    /**
     * Loads the sessions on the active tab's host for the session panel. The list already
     * shown stays while the new one loads, as long as it is for the same host.
     */
    fun listSessionsForActiveHost() {
        val tab = sessionRepository.activeTab.value ?: return
        val requestGeneration = ++sessionListGeneration
        val sourceTabId = tab.id
        val sourceHostId = tab.spec.hostId
        val spec = tab.spec.withDefaultMultiplexer()
        _state.update {
            val sameHost = sourceHostId != null && it.sessionsSourceHostId == sourceHostId
            it.copy(
                sessions = if (sameHost) it.sessions else emptyList(),
                sessionsLoading = true,
                sessionsError = null,
                sessionsSourceTabId = sourceTabId,
                sessionsSourceHostId = sourceHostId,
            )
        }
        // On the main thread: the listing picks a tmux connection from the open tabs, and
        // moves to the IO thread itself when it opens a new one.
        scope.launch(Dispatchers.Main) {
            val result = runCatching { sessionRepository.multiplexerSessions.list(spec) }
            val stale = requestGeneration != sessionListGeneration ||
                sessionRepository.activeTab.value?.id != sourceTabId
            if (stale) return@launch
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

    /**
     * Loads the list again each time the active tab's host reports that its sessions
     * changed, until cancelled. The panel runs this while it is open.
     */
    suspend fun reloadSessionsOnChange() {
        sessionRepository.multiplexerSessionsChanged.collect { sessionKey ->
            val spec = sessionRepository.activeTab.value?.spec ?: return@collect
            if (spec.usesRuntimeMultiplexer && spec.sessionKey == sessionKey) listSessionsForActiveHost()
        }
    }

    /** Opens a tab in a new session on the active tab's host. */
    fun createNextSession() {
        val tab = sessionRepository.activeTab.value ?: return
        val nextSpec = tab.spec.withNewSession()
        scope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.multiplexerSessions.resolveName(nextSpec) }
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
        if (!listMatchesActiveTab(sourceTabId)) return
        sessionRepository.switchActiveMultiplexerSession(name)
    }

    /**
     * Ends session [name] on the active tab's host from the panel, closes the tabs that
     * showed it, and loads the list again.
     */
    fun endSession(name: String, sourceTabId: String?) {
        if (!listMatchesActiveTab(sourceTabId)) return
        val spec = sessionRepository.activeTab.value?.spec?.withDefaultMultiplexer() ?: return
        _state.update { it.copy(sessionsLoading = true, sessionsError = null) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.multiplexerSessions.kill(spec, name) }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(sessionsLoading = false) }
                result.fold(
                    onSuccess = {
                        sessionRepository.closeMultiplexerSessionTabs(spec, name)
                        listSessionsForActiveHost()
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(sessionsError = error.toUiText(UiText.Resource(R.string.multiplexer_end_failed)))
                        }
                    },
                )
            }
        }
    }

    /** How many open tabs show session [name] on the active tab's host. */
    fun openTabCount(name: String): Int {
        val spec = sessionRepository.activeTab.value?.spec ?: return 0
        return sessionRepository.multiplexerSessions.tabsShowing(spec, name).size
    }

    /** Whether the panel's list still belongs to the active tab or its host; marks it stale when not. */
    private fun listMatchesActiveTab(sourceTabId: String?): Boolean {
        val activeTab = sessionRepository.activeTab.value
        val state = _state.value
        val activeHostId = activeTab?.spec?.hostId
        val listMatchesTab = sourceTabId != null &&
            state.sessionsSourceTabId == sourceTabId &&
            activeTab?.id == sourceTabId
        val listMatchesHost = activeHostId != null && state.sessionsSourceHostId == activeHostId
        if (activeTab != null && (listMatchesTab || listMatchesHost)) return true
        _state.value = state.copy(
            sessions = emptyList(),
            sessionsLoading = false,
            sessionsError = UiText.Resource(R.string.multiplexer_sessions_stale),
            sessionsSourceTabId = activeTab?.id,
            sessionsSourceHostId = activeHostId,
        )
        return false
    }
}

/** The sessions in [sessions] that a new tab on [spec]'s host can attach to, and which ones a tab shows. */
private fun RemoteMultiplexerSessions.choiceFor(
    spec: TabSpec,
    sessions: List<RemoteMultiplexerSession>,
): MultiplexerSessionChoice = MultiplexerSessionChoice(
    type = spec.multiplexer ?: MultiplexerRegistry.defaultType,
    sessions = sessions,
    openInTab = sessions.mapNotNullTo(mutableSetOf()) { session ->
        session.name.takeIf { tabsShowing(spec, it).isNotEmpty() }
    },
)

/** This state after a multiplexer check failed with [error], explained by [fallback] when it has no message. */
private fun MultiplexerUiState.failed(error: Throwable, fallback: UiText): MultiplexerUiState = copy(
    preflightLoading = false,
    preflightHostLabel = null,
    preflightError = error.toUiText(fallback),
    sessionChoice = null,
)

private fun TabSpec.withDefaultMultiplexer(): TabSpec =
    copy(multiplexer = multiplexer ?: MultiplexerRegistry.defaultType)

private fun TabSpec.withNewSession(): TabSpec = copy(
    multiplexer = multiplexer ?: MultiplexerRegistry.defaultType,
    multiplexerSessionName = null,
    multiplexerCreateIfMissing = true,
)
