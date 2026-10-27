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
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.ssh.TailscaleStatusChecker
import io.github.andreacovelli.abyssh.service.terminal.HostKeyPrompt
import io.github.andreacovelli.abyssh.service.terminal.SessionState
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseAction
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseButton
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserUiState
import io.github.andreacovelli.abyssh.ui.screens.files.FileEntryType
import io.github.andreacovelli.abyssh.ui.screens.files.FileSort
import io.github.andreacovelli.abyssh.ui.screens.files.UploadProgress
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSearchState
import io.github.andreacovelli.abyssh.ui.terminal.parseSearchMatches
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Session, multiplexer, and file-browser errors come from native and transport code as
// many exception types; the broad catches report them in UI state.
@Suppress("LargeClass", "TooManyFunctions", "TooGenericExceptionCaught")
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val tailscaleStatusChecker = TailscaleStatusChecker(application)
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val database = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(database.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(database.sshKeyDao())
    private val settingsRepository = SettingsRepository.getInstance(application)

    private sealed class PendingMultiplexerAction(open val spec: TabSpec) {
        data class Open(override val spec: TabSpec) : PendingMultiplexerAction(spec)
        data class Reconnect(val tabId: String, override val spec: TabSpec) : PendingMultiplexerAction(spec)
    }

    private val _multiplexerState = MutableStateFlow(MultiplexerUiState())
    val multiplexerState: StateFlow<MultiplexerUiState> = _multiplexerState.asStateFlow()
    private var pendingMultiplexerAction: PendingMultiplexerAction? = null
    private var multiplexerActionGeneration = 0L
    private var multiplexerSessionListGeneration = 0L
    private var multiplexerDuplicateInFlight = false

    private val _tailscaleActive = MutableStateFlow(tailscaleStatusChecker.isActive())
    val tailscaleActive: StateFlow<Boolean> = _tailscaleActive.asStateFlow()

    val tabs: StateFlow<List<TabSession>> = sessionRepository.tabs
    val activeTabId: StateFlow<String?> = sessionRepository.activeTabId
    val activeTab: StateFlow<TabSession?> = sessionRepository.activeTab
    val sessionState: StateFlow<SessionState> = sessionRepository.sessionState
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt
    val hosts: StateFlow<List<HostProfile>> =
        hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hostsLoaded: StateFlow<Boolean> =
        hosts.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        sessionRepository.attachClient()
    }

    private val _search = MutableStateFlow<TerminalSearchState?>(null)
    /** The open search bar, or null when it's closed. */
    val search: StateFlow<TerminalSearchState?> = _search.asStateFlow()
    private var searchJob: Job? = null

    fun openSearch() {
        if (_search.value == null) _search.value = TerminalSearchState()
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    fun setSearchQuery(query: String) {
        val state = _search.value ?: return
        if (state.query == query) return
        _search.value = state.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_TYPING_DELAY_MS)
            val found = parseSearchMatches(sessionRepository.search(query))
            _search.update { it?.copy(matches = found, current = if (found.isEmpty()) -1 else 0) }
            scrollToCurrentMatch()
        }
    }

    /** Up arrow: the next match toward older output. */
    fun searchOlder() = stepSearch(TerminalSearchState::older)

    /** Down arrow: the next match toward newer output. */
    fun searchNewer() = stepSearch(TerminalSearchState::newer)

    private fun stepSearch(step: (TerminalSearchState) -> TerminalSearchState) {
        val state = _search.value ?: return
        if (state.query.isEmpty()) return
        _search.value = step(state)
        scrollToCurrentMatch()
        // Output may have arrived since the last search: refresh the matches while
        // keeping the one just selected.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val found = parseSearchMatches(sessionRepository.search(state.query))
            _search.update { it?.withMatches(found) }
        }
    }

    private val _tappedLink = MutableStateFlow<String?>(null)
    /** The link the user tapped, shown with open and copy until dismissed. */
    val tappedLink: StateFlow<String?> = _tappedLink.asStateFlow()

    /**
     * A tap where links may open. [textUrl] is the URL written at that cell, if any;
     * otherwise the cell may still carry an OSC 8 hyperlink. [noLink] runs when it has none.
     */
    fun onLinkTap(col: Int, row: Int, textUrl: String?, noLink: () -> Unit) {
        if (textUrl != null) {
            _tappedLink.value = textUrl
            return
        }
        viewModelScope.launch {
            val hyperlink = sessionRepository.hyperlinkAt(col, row)
            _tappedLink.value = hyperlink
            if (hyperlink == null) noLink()
        }
    }

    fun dismissLink() {
        _tappedLink.value = null
    }

    init {
        // Search matches and links belong to one terminal, so switching tabs drops them.
        viewModelScope.launch {
            activeTabId.collect {
                closeSearch()
                dismissLink()
            }
        }
    }

    private fun scrollToCurrentMatch() {
        _search.value?.currentMatch?.let { sessionRepository.scrollToScreenRow(it.startRow) }
    }

    private val connectionTabByTab = MutableStateFlow<Map<String, ConnectionTab>>(emptyMap())
    private val fileBrowserStateByTab =
        MutableStateFlow<Map<String, FileBrowserUiState>>(emptyMap())
    private val fileHomeByTab = ConcurrentHashMap<String, String>()
    // One in-flight job per tab for each operation; launching a new one cancels
    // the previous, so stale responses can never overwrite newer state.
    private val fileBrowserRefreshJobs = ConcurrentHashMap<String, Job>()
    private val fileBrowserResolverJobs = ConcurrentHashMap<String, Job>()

    val selectedTab: StateFlow<ConnectionTab> =
        combine(activeTabId, connectionTabByTab) { id, map ->
                if (id == null) ConnectionTab.Terminal else map[id] ?: ConnectionTab.Terminal
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionTab.Terminal)

    val fileBrowserState: StateFlow<FileBrowserUiState> =
        combine(activeTabId, fileBrowserStateByTab) { id, map ->
                if (id == null) FileBrowserUiState() else map[id] ?: FileBrowserUiState()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FileBrowserUiState())

    fun selectTabForHost(hostId: Long?): TabSession? {
        val existing = sessionRepository.tabsForHost(hostId)
        if (existing.isEmpty()) return null
        val activeId = sessionRepository.activeTabId.value
        val target = existing.firstOrNull { it.id == activeId } ?: existing.last()
        sessionRepository.selectTab(target.id)
        return target
    }

    fun selectLocalShellTab(): TabSession? {
        val existing =
            sessionRepository.tabs.value.filter {
                it.spec.hostId == null && it.spec.transport == Transport.LocalShell
            }
        if (existing.isEmpty()) return null
        val activeId = sessionRepository.activeTabId.value
        val target = existing.firstOrNull { it.id == activeId } ?: existing.last()
        sessionRepository.selectTab(target.id)
        return target
    }

    fun openTab(spec: TabSpec): TabSession {
        refreshTailscaleStatus()
        return sessionRepository.openTab(spec)
    }

    data class PreparedTabOpen(
        val spec: TabSpec,
        val requiresVerification: Boolean,
    )

    suspend fun prepareTabOpenForHost(hostId: Long): PreparedTabOpen? {
        val host = hostRepository.getById(hostId) ?: return null
        return prepareTabOpen(host)
    }

    suspend fun prepareTabOpen(host: HostProfile): PreparedTabOpen {
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

    fun duplicateActiveTab(): TabSession? {
        val current = sessionRepository.activeTab.value ?: return null
        if (current.spec.usesRuntimeMultiplexer) {
            startMultiplexerDuplicate(current.spec)
            return null
        }
        return openTab(current.spec)
    }

    fun duplicateTab(tabId: String): TabSession? {
        val tab = sessionRepository.tabs.value.firstOrNull { it.id == tabId } ?: return null
        if (tab.spec.usesRuntimeMultiplexer) {
            startMultiplexerDuplicate(tab.spec)
            return null
        }
        return openTab(tab.spec)
    }

    private fun startMultiplexerDuplicate(spec: TabSpec) {
        if (multiplexerDuplicateInFlight) return
        multiplexerDuplicateInFlight = true
        _multiplexerState.value =
            _multiplexerState.value.copy(
                duplicateLoading = true,
                preflightError = null,
            )
        val duplicateSpec = spec.copy(
            multiplexer = spec.multiplexer ?: MultiplexerRegistry.defaultType,
            multiplexerSessionName = null,
            multiplexerCreateIfMissing = true,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { sessionRepository.resolveMultiplexerSessionName(duplicateSpec) }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { name ->
                            openTab(duplicateSpec.copy(multiplexerSessionName = name))
                        },
                        onFailure = { error ->
                            _multiplexerState.value = _multiplexerState.value.copy(
                                preflightError = error.message ?: "Could not create multiplexer session",
                            )
                        },
                    )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    multiplexerDuplicateInFlight = false
                    _multiplexerState.value = _multiplexerState.value.copy(duplicateLoading = false)
                }
            }
        }
    }

    fun initiateMultiplexerOpen(spec: TabSpec) {
        beginMultiplexerAction(PendingMultiplexerAction.Open(spec))
    }

    private fun initiateMultiplexerReconnect(tab: TabSession) {
        beginMultiplexerAction(PendingMultiplexerAction.Reconnect(tab.id, tab.spec))
    }

    private fun beginMultiplexerAction(action: PendingMultiplexerAction) {
        val preparedAction = when (action) {
            is PendingMultiplexerAction.Open -> action.copy(
                spec = action.spec.copy(multiplexer = action.spec.multiplexer ?: MultiplexerRegistry.defaultType),
            )
            is PendingMultiplexerAction.Reconnect -> action.copy(
                spec = action.spec.copy(multiplexer = action.spec.multiplexer ?: MultiplexerRegistry.defaultType),
            )
        }
        if (
            pendingMultiplexerAction == preparedAction &&
                _multiplexerState.value.preflightLoading
        ) {
            // Keep the original generation alive while its host-key prompt is awaiting a decision.
            return
        }
        val actionGeneration = ++multiplexerActionGeneration
        pendingMultiplexerAction = preparedAction
        val spec = preparedAction.spec
        val label = spec.multiplexer?.label ?: MultiplexerRegistry.defaultType.label
        val reconnectRecovery = preparedAction is PendingMultiplexerAction.Reconnect
        if (spec.transport == Transport.Mosh) {
            _multiplexerState.value = MultiplexerUiState(
                preflightError = "$label is not supported for Mosh connections",
                reconnectRecovery = reconnectRecovery,
            )
            return
        }
        if (spec.multiplexer != null && spec.multiplexer.runtimeSupported.not()) {
            _multiplexerState.value = MultiplexerUiState(
                preflightError = "${spec.multiplexer.label} is not supported yet",
                reconnectRecovery = reconnectRecovery,
            )
            return
        }
        _multiplexerState.value = MultiplexerUiState(
            preflightLoading = true,
            preflightHostLabel = spec.tabLabel,
            reconnectRecovery = reconnectRecovery,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                try {
                    Result.success(
                        sessionRepository.resolveMultiplexerSessionName(
                            spec = spec,
                            reuseDetachedAbysshSession =
                                preparedAction is PendingMultiplexerAction.Open &&
                                    spec.multiplexerSessionName.isNullOrBlank(),
                        ),
                    )
                } catch (_: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        reportMultiplexerActionStopped(actionGeneration, preparedAction)
                    }
                    return@launch
                } catch (error: Exception) {
                    Result.failure(error)
                }
            withContext(Dispatchers.Main) {
                if (isCurrentMultiplexerAction(actionGeneration, preparedAction)) {
                    result.fold(
                        onSuccess = { sessionName ->
                            completeMultiplexerAction(actionGeneration, preparedAction, sessionName)
                        },
                        onFailure = { error ->
                            _multiplexerState.value = _multiplexerState.value.copy(
                                preflightLoading = false,
                                preflightHostLabel = null,
                                preflightError = error.message ?: "Could not prepare multiplexer session",
                            )
                        },
                    )
                } else {
                    reportMultiplexerActionStopped(actionGeneration, preparedAction)
                }
            }
        }
    }

    private fun isCurrentMultiplexerAction(actionGeneration: Long, action: PendingMultiplexerAction): Boolean =
        multiplexerActionGeneration == actionGeneration && pendingMultiplexerAction == action

    private fun completeMultiplexerAction(
        actionGeneration: Long,
        action: PendingMultiplexerAction,
        sessionName: String,
    ) {
        if (isCurrentMultiplexerAction(actionGeneration, action)) {
            val nextSpec = action.spec.copy(
                multiplexer = action.spec.multiplexer ?: MultiplexerRegistry.defaultType,
                multiplexerSessionName = sessionName,
                multiplexerCreateIfMissing = action.spec.multiplexerCreateIfMissing,
            )
            pendingMultiplexerAction = null
            _multiplexerState.value = MultiplexerUiState()
            when (action) {
                is PendingMultiplexerAction.Open -> openTab(nextSpec)
                is PendingMultiplexerAction.Reconnect -> {
                    if (!reconnectTabWithSpec(action.tabId, nextSpec)) {
                        _multiplexerState.value = MultiplexerUiState(
                            preflightError = "Terminal session is no longer available",
                            reconnectRecovery = true,
                        )
                    }
                }
            }
        } else {
            reportMultiplexerActionStopped(actionGeneration, action)
        }
    }

    private fun reportMultiplexerActionStopped(
        actionGeneration: Long,
        action: PendingMultiplexerAction,
    ) {
        when {
            isCurrentMultiplexerAction(actionGeneration, action) -> {
                _multiplexerState.value = _multiplexerState.value.copy(
                    preflightLoading = false,
                    preflightHostLabel = null,
                    preflightError = "Connection preparation was cancelled",
                )
            }

            pendingMultiplexerAction == null -> {
                _multiplexerState.value = MultiplexerUiState(
                    preflightError = "Connection preparation was superseded",
                    reconnectRecovery = action is PendingMultiplexerAction.Reconnect,
                )
            }
        }
    }

    private fun reconnectTabWithSpec(tabId: String, spec: TabSpec): Boolean {
        val tab = sessionRepository.tabs.value.firstOrNull { it.id == tabId } ?: return false
        refreshTailscaleStatus()
        tab.spec = spec
        sessionRepository.reconnectTab(tab)
        return true
    }

    fun listMultiplexerSessionsForCurrentHost() {
        val tab = sessionRepository.activeTab.value ?: return
        val requestGeneration = ++multiplexerSessionListGeneration
        val sourceTabId = tab.id
        val sourceHostId = tab.spec.hostId
        val spec = tab.spec.copy(multiplexer = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType)
        _multiplexerState.value = _multiplexerState.value.copy(
            sessions = emptyList(),
            sessionsLoading = true,
            sessionsError = null,
            sessionsSourceTabId = sourceTabId,
            sessionsSourceHostId = sourceHostId,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.listMultiplexerSessions(spec) }
            withContext(Dispatchers.Main) {
                val activeTab = sessionRepository.activeTab.value
                if (requestGeneration != multiplexerSessionListGeneration || activeTab?.id != sourceTabId) {
                    return@withContext
                }
                result.fold(
                    onSuccess = { sessions ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            sessions = sessions,
                            sessionsLoading = false,
                            sessionsError = null,
                            sessionsSourceTabId = sourceTabId,
                            sessionsSourceHostId = sourceHostId,
                        )
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            sessions = emptyList(),
                            sessionsLoading = false,
                            sessionsError = error.message ?: "Failed to list sessions",
                            sessionsSourceTabId = sourceTabId,
                            sessionsSourceHostId = sourceHostId,
                        )
                    },
                )
            }
        }
    }

    fun createNextMultiplexerSession() {
        val tab = sessionRepository.activeTab.value ?: return
        val nextSpec = tab.spec.copy(
            multiplexer = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType,
            multiplexerSessionName = null,
            multiplexerCreateIfMissing = true,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { sessionRepository.resolveMultiplexerSessionName(nextSpec) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        openTab(nextSpec.copy(multiplexerSessionName = name))
                        listMultiplexerSessionsForCurrentHost()
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            sessionsError = error.message ?: "Could not create multiplexer session",
                        )
                    },
                )
            }
        }
    }

    fun switchToMultiplexerSession(name: String, sourceTabId: String?) {
        val activeTab = sessionRepository.activeTab.value
        val state = _multiplexerState.value
        val activeHostId = activeTab?.spec?.hostId
        val sessionListMatchesTab = sourceTabId != null && state.sessionsSourceTabId == sourceTabId && activeTab?.id == sourceTabId
        val sessionListMatchesHost = activeHostId != null && state.sessionsSourceHostId == activeHostId
        if (activeTab == null || (!sessionListMatchesTab && !sessionListMatchesHost)) {
            _multiplexerState.value = state.copy(
                sessions = emptyList(),
                sessionsLoading = false,
                sessionsError = "Session list is stale. Refresh sessions and try again.",
                sessionsSourceTabId = activeTab?.id,
                sessionsSourceHostId = activeHostId,
            )
            return
        }
        sessionRepository.switchActiveMultiplexerSession(name)
    }

    fun retryPendingMultiplexerOpen() {
        val action = pendingMultiplexerAction ?: return
        beginMultiplexerAction(action)
    }

    fun connectPendingWithoutMultiplexer(): Boolean {
        val action = pendingMultiplexerAction ?: return false
        val plainSpec = action.spec.copy(
            multiplexer = null,
            multiplexerSessionName = null,
            multiplexerCreateIfMissing = true,
        )
        _multiplexerState.value = MultiplexerUiState()
        pendingMultiplexerAction = null
        multiplexerActionGeneration += 1
        return when (action) {
            is PendingMultiplexerAction.Open -> {
                openTab(plainSpec)
                true
            }
            is PendingMultiplexerAction.Reconnect -> reconnectTabWithSpec(action.tabId, plainSpec)
        }
    }

    fun dismissMultiplexerRecovery(onBack: () -> Unit) {
        val shouldNavigateBack = pendingMultiplexerAction is PendingMultiplexerAction.Open
        _multiplexerState.value = MultiplexerUiState()
        pendingMultiplexerAction = null
        multiplexerActionGeneration += 1
        if (shouldNavigateBack) onBack()
    }

    fun selectTab(id: String) {
        sessionRepository.selectTab(id)
    }

    fun closeTab(id: String) {
        connectionTabByTab.value = connectionTabByTab.value - id
        fileBrowserStateByTab.value = fileBrowserStateByTab.value - id
        fileHomeByTab.remove(id)
        fileBrowserResolverJobs.remove(id)?.cancel()
        fileBrowserRefreshJobs.remove(id)?.cancel()
        sessionRepository.closeTab(id)
    }

    fun reconnect() {
        refreshTailscaleStatus()
        val tab = sessionRepository.activeTab.value ?: return
        if (tab.spec.usesRuntimeMultiplexer) {
            initiateMultiplexerReconnect(tab)
        } else {
            sessionRepository.reconnectActive()
        }
    }

    fun selectConnectionTab(tab: ConnectionTab) {
        val id = activeTabId.value ?: return
        connectionTabByTab.value = connectionTabByTab.value + (id to tab)
        if (tab == ConnectionTab.Files) {
            val state = fileBrowserStateByTab.value[id]
            if (state == null || state.entries.isEmpty()) {
                resolveInitialFilePathAndRefresh(id)
            }
        }
    }

    private fun updateFileBrowserState(
        id: String,
        transform: (FileBrowserUiState) -> FileBrowserUiState,
    ) {
        fileBrowserStateByTab.update { current ->
            val previous = current[id] ?: FileBrowserUiState()
            current + (id to transform(previous))
        }
    }

    private fun tabExists(tabId: String): Boolean = sessionRepository.tabs.value.any { it.id == tabId }

    private fun currentSessionPwd(tabId: String): String? =
        sessionRepository.tabs.value
            .firstOrNull { it.id == tabId }
            ?.sessionState
            ?.value
            ?.pwd
            ?.takeIf { it.isNotBlank() }

    private suspend fun resolveRealpath(tabId: String, path: String): String? =
        try {
            sessionRepository.sftpRealpath(tabId, path).takeIf { it.isNotBlank() }
        } catch (err: CancellationException) {
            throw err
        } catch (_: Exception) {
            null
        }

    private fun resolveInitialFilePathAndRefresh(tabId: String) {
        if (!tabExists(tabId)) return
        val pwd = currentSessionPwd(tabId)
        if (pwd != null) {
            updateFileBrowserState(tabId) { it.copy(currentPath = pwd, resolvedHomePath = pwd) }
            refreshFileBrowser(tabId)
            return
        }
        val cachedHome = fileHomeByTab[tabId]
        if (cachedHome != null) {
            updateFileBrowserState(tabId) { it.copy(currentPath = cachedHome) }
            refreshFileBrowser(tabId)
            return
        }
        fileBrowserResolverJobs.remove(tabId)?.cancel()
        val resolverJob =
            viewModelScope.launch(Dispatchers.IO) {
                val tabState = sessionRepository.tabs.value.firstOrNull { it.id == tabId }
                val fallback = tabState?.engine?.state?.value?.pwd?.takeIf { it.isNotBlank() } ?: "/"
                val resolved = resolveRealpath(tabId, ".") ?: resolveRealpath(tabId, "~")
                if (!isActive || !tabExists(tabId)) return@launch
                val home = resolved ?: fallback
                val initial = currentSessionPwd(tabId) ?: home
                fileHomeByTab[tabId] = home
                updateFileBrowserState(tabId) {
                    it.copy(currentPath = initial, resolvedHomePath = initial)
                }
                refreshFileBrowser(tabId)
            }
        fileBrowserResolverJobs[tabId] = resolverJob
        resolverJob.invokeOnCompletion { fileBrowserResolverJobs.remove(tabId, resolverJob) }
    }

    fun refreshFileBrowser() {
        val tabId = activeTabId.value ?: return
        refreshFileBrowser(tabId)
    }

    private fun refreshFileBrowser(tabId: String) {
        if (!tabExists(tabId)) return
        val pwd =
            sessionRepository.tabs.value
                .firstOrNull { it.id == tabId }
                ?.sessionState
                ?.value
                ?.pwd
                ?.takeIf { it.isNotBlank() } ?: "/"
        val targetPath = (fileBrowserStateByTab.value[tabId]?.currentPath?.ifBlank { pwd }) ?: pwd
        fileBrowserRefreshJobs.remove(tabId)?.cancel()
        updateFileBrowserState(tabId) {
            it.copy(currentPath = targetPath, isLoading = true, error = null)
        }
        val refreshJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val rows = sessionRepository.sftpListDirectory(tabId, targetPath)
                    if (!isActive) return@launch
                    val entries =
                        rows.map { row ->
                            val parts = row.split('\t')
                            val name = parts.getOrNull(0).orEmpty()
                            val kind = parts.getOrNull(1).orEmpty()
                            val size = parts.getOrNull(2)?.toLongOrNull()
                            val mtime = parts.getOrNull(3)?.toLongOrNull()
                            val normalizedBase = targetPath.trimEnd('/').ifEmpty { "/" }
                            val type =
                                when (kind) {
                                    "dir" -> FileEntryType.Directory
                                    "file" -> FileEntryType.File
                                    "link" -> FileEntryType.Symlink
                                    else -> FileEntryType.Other
                                }
                            FileBrowserEntry(
                                name = name,
                                path =
                                    if (normalizedBase == "/") {
                                        "/$name"
                                    } else {
                                        "$normalizedBase/$name"
                                    },
                                type = type,
                                sizeBytes = if (size == null || size <= 0L) null else size,
                                modifiedAtText = mtime?.takeIf { it > 0L }?.let { formatMtime(it) },
                            )
                        }
                    val current = fileBrowserStateByTab.value[tabId] ?: FileBrowserUiState()
                    val sortedEntries =
                        when (current.sort) {
                            FileSort.Name -> entries.sortedBy { it.name.lowercase() }
                            FileSort.Size -> entries.sortedByDescending { it.sizeBytes ?: -1L }
                            FileSort.Modified ->
                                entries.sortedByDescending { it.modifiedAtText ?: "" }
                        }
                    if (isActive && current.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(entries = sortedEntries, isLoading = false, error = null)
                        }
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    val current = fileBrowserStateByTab.value[tabId]
                    if (isActive && current?.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(
                                isLoading = false,
                                error = err.message ?: "Failed to list directory",
                            )
                        }
                    }
                }
            }
        fileBrowserRefreshJobs[tabId] = refreshJob
        refreshJob.invokeOnCompletion { fileBrowserRefreshJobs.remove(tabId, refreshJob) }
    }

    suspend fun beginUpload(fileName: String) {
        val tabId = activeTabId.value ?: return
        val current = fileBrowserStateByTab.value[tabId] ?: return
        val targetPath = current.currentPath.trimEnd('/') + "/" + fileName
        sessionRepository.awaitTabConnected(tabId)
        sessionRepository.sftpOpenWrite(tabId, targetPath)
    }

    suspend fun writeUploadChunk(data: ByteArray) {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpWriteChunk(tabId, data)
    }

    suspend fun finishUpload() {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpCloseWrite(tabId)
        refreshFileBrowser()
    }

    fun setUploadProgress(progress: UploadProgress?) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(uploadProgress = progress) }
    }

    fun goUpDirectory() {
        val tabId = activeTabId.value ?: return
        val current = fileBrowserStateByTab.value[tabId]?.currentPath ?: return
        val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
        openPath(parent)
    }

    fun openPath(path: String) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(currentPath = path) }
        refreshFileBrowser()
    }

    fun selectFileSort(sort: FileSort) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(sort = sort) }
        refreshFileBrowser()
    }

    suspend fun deleteFile(tabId: String, entry: FileBrowserEntry) {
        sessionRepository.sftpDelete(tabId, entry.path, entry.type == FileEntryType.Directory)
        withContext(Dispatchers.Main) { refreshFileBrowser() }
    }

    suspend fun readFile(tabId: String, entry: FileBrowserEntry, maxBytes: Int): ByteArray {
        sessionRepository.awaitTabConnected(tabId)
        return sessionRepository.sftpReadFile(tabId, entry.path, maxBytes)
    }

    private fun formatMtime(epochSeconds: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochSeconds * 1000L))
    }

    fun refreshTailscaleStatus() {
        _tailscaleActive.value = tailscaleStatusChecker.isActive()
    }

    fun onCanvasSizeChanged(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        sessionRepository.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun onScroll(delta: Int, x: Float, y: Float) {
        sessionRepository.scroll(delta, x, y)
    }

    fun onPrimaryMouseClick(x: Float, y: Float) {
        sendPrimaryMouseEvent(TerminalMouseAction.Press, x, y, anyButtonPressed = false, trackLastCell = false)
        sendPrimaryMouseEvent(TerminalMouseAction.Release, x, y, anyButtonPressed = false, trackLastCell = false)
    }

    fun onSecondaryMouseClick(x: Float, y: Float) {
        for (action in listOf(TerminalMouseAction.Press, TerminalMouseAction.Release)) {
            sessionRepository.sendMouseEvent(
                action = action,
                button = TerminalMouseButton.Right,
                mods = 0,
                x = x,
                y = y,
                anyButtonPressed = false,
                trackLastCell = false,
            )
        }
    }

    /** Press, motion or release of a non-left button from a physical mouse. */
    fun onMouseButton(button: Int, action: Int, x: Float, y: Float) {
        sessionRepository.sendMouseEvent(
            action = action,
            button = button,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = action != TerminalMouseAction.Release,
            trackLastCell = true,
        )
    }

    fun onAppSelectionDrag(action: Int, x: Float, y: Float) {
        sendPrimaryMouseEvent(
            action = action,
            x = x,
            y = y,
            anyButtonPressed = action != TerminalMouseAction.Release,
            trackLastCell = true,
        )
    }

    private fun sendPrimaryMouseEvent(
        action: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) {
        sessionRepository.sendMouseEvent(
            action = action,
            button = TerminalMouseButton.Left,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = anyButtonPressed,
            trackLastCell = trackLastCell,
        )
    }

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int = 0) {
        val hasNonTextModifier = mods and ((1 shl 1) or (1 shl 2) or (1 shl 3)) != 0
        val isRelease = action == GhosttyKeyAction.Release
        if (!isRelease) {
            sessionRepository.scrollToActive()
        }
        val effectiveCodepoint = if (charCode > 0) charCode else codepoint

        val utf8 =
            if (effectiveCodepoint > 0 && !hasNonTextModifier && !isRelease) {
                effectiveCodepoint.toChar().toString()
            } else {
                null
            }
        sessionRepository.writeKey(key, effectiveCodepoint, mods, action, utf8)
    }

    fun onTextInput(text: String) {
        sessionRepository.scrollToActive()
        sessionRepository.writeText(text)
    }

    fun onSpecialKeyInput(key: TerminalSpecialKey, mods: Int) {
        sessionRepository.scrollToActive()
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Press)
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Release)
    }

    fun onPasteText(text: String) {
        if (text.isEmpty()) return
        sessionRepository.scrollToActive()
        sessionRepository.writePaste(text)
    }

    fun onFocusChanged(focused: Boolean) {
        sessionRepository.sendFocusEvent(focused)
    }

    fun onColorSchemeChanged(isDark: Boolean) {
        sessionRepository.setColorScheme(isDark)
    }

    fun onDefaultColorsChanged(
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) {
        sessionRepository.setDefaultColors(fg, bg, cursor, palette)
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    override fun onCleared() {
        sessionRepository.detachClient()
    }

    companion object {
        private const val SEARCH_TYPING_DELAY_MS = 150L

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

data class MultiplexerUiState(
    val preflightError: String? = null,
    val preflightLoading: Boolean = false,
    val preflightHostLabel: String? = null,
    val duplicateLoading: Boolean = false,
    val sessions: List<RemoteMultiplexerSession> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val sessionsSourceTabId: String? = null,
    val sessionsSourceHostId: Long? = null,
    val reconnectRecovery: Boolean = false,
)
