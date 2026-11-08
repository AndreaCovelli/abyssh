package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.network.NetworkAvailability
import io.github.andreacovelli.abyssh.service.network.defaultNetworkChanges

import android.app.Application
import android.util.Log
import io.github.andreacovelli.abyssh.ui.components.plainTextClip
import android.content.ClipboardManager
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TerminalSessions"

/**
 * The open terminal tabs and which one is active. Each tab owns a
 * [TerminalSessionEngine]; send input and terminal queries to the active tab's engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionRepository private constructor(application: Application) {
    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hostKeyStore =
        HostKeyStore(appContext.getSharedPreferences(HostKeyStore.PREFS_NAME, Application.MODE_PRIVATE))
    private val localNetwork = LocalNetworkAccess(appContext)
    private val network = NetworkAvailability(appContext)
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val preflight = MultiplexerPreflight(hostKeyStore)
    val multiplexerSessions = RemoteMultiplexerSessions(
        preflight = preflight,
        tmuxControl = { tmuxTabs.connectedTo(it) },
        tabs = { _tabs.value },
    )
    private val foreground = SessionForegroundTracker(appContext)

    /** File transfers, which keep running while the terminal screen is closed. */
    val fileTransfers = FileTransferQueue(scope, Dispatchers.IO, ContentResolverFiles(appContext)) { tabId ->
        withSftp(tabId) { it }
    }

    // A frame of the display while the setting asks to match its refresh rate.
    private val frameInterval =
        DisplayFrameInterval(appContext, settingsRepository.matchDisplayRefreshRate, scope)
    private var attachedClients = 0

    private val _multiplexerSessionsChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * The [TabSpec.sessionKey] of a host whose tmux sessions changed, as an open tmux
     * control-mode connection reported.
     */
    val multiplexerSessionsChanged: SharedFlow<String> = _multiplexerSessionsChanged.asSharedFlow()

    /** Messages for the user about tabs, such as a host falling back from tmux control mode. */
    val notices = TerminalNotices()

    private val _tabs = MutableStateFlow<List<TabSession>>(emptyList())
    val tabs: StateFlow<List<TabSession>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabSession?> =
        combine(_tabs, _activeTabId) { tabs, id -> tabs.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val sessionState: StateFlow<SessionState> =
        activeTab
            .flatMapLatest { tab -> tab?.sessionState ?: flowOf(SessionState()) }
            .stateIn(scope, SharingStarted.Eagerly, SessionState())

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> =
        combine(preflight.hostKeyPrompt, activeTab.flatMapLatest { it?.hostKeyPrompt ?: flowOf(null) }) {
                preflight,
                active,
            ->
            preflight ?: active
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The tabs that are connected or on their way there, again whenever the tabs change. */
    private val liveTabs: Flow<List<TabSession>> =
        _tabs
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tabs.map { tab -> tab.isLiveFlow().map { live -> tab.takeIf { live } } }) {
                        it.filterNotNull()
                    }
                }
            }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val connectedHostIds: StateFlow<Set<Long>> =
        liveTabs
            .map { tabs -> tabs.mapNotNullTo(mutableSetOf()) { it.spec.hostId } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val connectedHosts: Flow<Set<Long>> =
        _tabs
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptySet())
                } else {
                    combine(
                        tabs.map { tab ->
                            tab.sessionState.map { state -> tab.spec.hostId.takeIf { state.status == SessionStatus.Connected } }
                        },
                    ) { hostIds -> hostIds.filterNotNull().toSet() }
                }
            }
            .distinctUntilChanged()

    init {
        scope.launch {
            recordNewConnections(connectedHosts) { id ->
                // Only the sort order depends on this, so a failed write isn't worth a crash.
                runCatching {
                    AppDatabase.getInstance(appContext).hostOrganizationDao().setLastConnected(id, System.currentTimeMillis())
                }.onFailure { Log.w(TAG, "Couldn't record the connection time", it) }
            }
        }
    }

    private val tmuxTabs =
        TmuxControlTabs(
            scope = scope,
            hostKeyStore = hostKeyStore,
            localNetwork = localNetwork,
            network = network,
            tabs = { _tabs.value },
            addTab = { spec, control, windowId, select -> addTab(spec, control, windowId, select) },
            removeTab = ::removeTab,
            selectTab = ::selectTab,
            orderTabs = { ordered -> _tabs.value = reorderedTabs(_tabs.value, ordered) },
            sessionsChanged = { _multiplexerSessionsChanged.tryEmit(it.sessionKey) },
        )

    init {
        scope.launch {
            combine(liveTabs, activeTab) { live, active ->
                sessionNotification(_tabs.value, active, anyLive = live.isNotEmpty())
            }.collect(foreground::update)
        }
        scope.launch { activeTab.collect(tmuxTabs::activeTabChanged) }
        // After a network change, each SSH connection checks that its server still
        // answers, so one left on the old network reconnects within seconds.
        scope.launch {
            liveTabs
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .flatMapLatest { live -> if (live) defaultNetworkChanges(appContext) else emptyFlow() }
                .collect {
                    Log.i(TAG, "Default network changed; probing SSH connections")
                    val tabs = _tabs.value
                    tabs.forEach { it.engine.networkProbe?.probe() }
                    tabs.mapNotNull { it.engine.tmuxControl }.distinct().forEach { it.networkProbe.probe() }
                }
        }
    }

    fun attachClient() {
        attachedClients += 1
    }

    fun detachClient() {
        attachedClients = (attachedClients - 1).coerceAtLeast(0)
    }

    fun tabsForHost(hostId: Long?): List<TabSession> = _tabs.value.filter { it.spec.hostId == hostId }

    /** SFTP for a remote tab, or null for the local shell and closed tabs. */
    fun sftpFor(tabId: String): SftpSession? =
        _tabs.value.firstOrNull { it.id == tabId && it.spec.transport != Transport.LocalShell }?.engine?.sftp

    /**
     * Opens a tab for [spec]. A tmux control-mode tab joins its host's control session,
     * which opens a tab for each tmux window; a host whose tmux is too old for control
     * mode gets a plain tmux tab and a notice.
     */
    fun openTab(spec: TabSpec): TabSession {
        if (!spec.usesTmuxControlMode) return addTab(spec)
        if (!TmuxControlSupport.isUnsupported(spec)) return tmuxTabs.open(spec)
        notices.notify(UiText.Resource(R.string.tmux_control_unsupported))
        return addTab(spec.copy(tmuxControlMode = false))
    }

    /**
     * Opens a new tmux window, and so a tab, on the control session for [spec]'s host;
     * false when there is none. While that session isn't attached, it opens nothing and
     * tells the user why.
     */
    fun openTmuxWindow(spec: TabSpec): Boolean {
        if (!spec.usesTmuxControlMode) return false
        return when (tmuxTabs.openWindow(spec)) {
            TmuxControlTabs.OpenWindowResult.Opened -> true
            TmuxControlTabs.OpenWindowResult.Offline -> {
                notices.notify(UiText.Resource(R.string.tmux_window_needs_connection))
                true
            }
            TmuxControlTabs.OpenWindowResult.NoSession -> false
        }
    }

    private fun addTab(
        spec: TabSpec,
        tmuxControl: TmuxControlSession? = null,
        tmuxWindowId: Int? = null,
        select: Boolean = true,
    ): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                publishClipboardText = { text -> publishTerminalClipboard(id, text) },
                scrollbackLimitBytes = {
                    settingsRepository.terminalScrollbackMb.value * SettingsRepository.BYTES_PER_MB
                },
                snapshotIntervalMs = { frameInterval.ms },
                scope = scope,
                localShellService = NativeLocalShellService(appContext.filesDir, appContext.cacheDir),
                hostKeyStore = hostKeyStore,
                localNetwork = localNetwork,
                network = network,
                onShellExited = { scope.launch { endTab(id) } },
                tmuxControl = tmuxControl,
                tmuxWindowId = tmuxWindowId,
            )
        val tab = TabSession(id, spec, engine, number = nextTabNumber(_tabs.value, spec))
        _tabs.value += tab
        if (select || _activeTabId.value == null) _activeTabId.value = id
        reconnectTab(tab)
        return tab
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /**
     * Closes tab [id] and ends what it runs. A tmux window's tab kills its window and the
     * programs in it, unless it is the session's last tab, which detaches and leaves the
     * session running; the UI asks the user before a kill ([disconnectTabs] never kills).
     */
    fun endTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (tmuxTabs.closeNeedsConnection(tab)) {
            notices.notify(UiText.Resource(R.string.tmux_window_needs_connection))
            return
        }
        val windowId = tab.engine.tmuxWindowId
        removeTab(tab)
        tmuxTabs.tabClosed(tab, windowId)
    }

    private fun removeTab(tab: TabSession) {
        val tabs = _tabs.value
        if (tab !in tabs) return
        _activeTabId.value = tabAfterClose(tabs, tab.id, _activeTabId.value)
        _tabs.value = tabs - tab
        fileTransfers.releaseTabThen(tab.id, tab.engine.sftp, tab.engine::dispose)
    }

    /**
     * Stops the active tab's connection attempt; the tab stays, with a Reconnect button.
     * A tmux control-mode tab ends its control session instead, which closes its tabs.
     */
    fun cancelActiveConnect() {
        val tab = activeTab.value ?: return
        val control = tab.engine.tmuxControl
        if (control != null) {
            tmuxTabs.end(control)
        } else {
            tab.engine.disconnect(UiText.Resource(R.string.error_connection_cancelled))
        }
    }

    /** Runs [action] on the active tab's tmux window, or says to wait while tmux reconnects. */
    fun runTmuxAction(action: TmuxWindowAction) {
        val tab = activeTab.value ?: return
        if (!tmuxTabs.run(tab, action) && tab.engine.tmuxControl != null) {
            notices.notify(UiText.Resource(R.string.tmux_action_needs_connection))
        }
    }

    /** Closes the tabs of a multiplexer session that ended, without sending tmux anything more. */
    fun closeMultiplexerSessionTabs(spec: TabSpec, name: String) {
        disconnectTabs(multiplexerSessions.tabsShowing(spec, name).map { it.id })
    }

    /**
     * Closes tabs [ids] without ending anything on the server. A tmux control-mode tab
     * detaches its whole session, which closes the session's other tabs too and leaves
     * every window running; [endTab] would end the tab's window instead.
     */
    fun disconnectTabs(ids: Collection<String>) {
        val tabs = _tabs.value.filter { it.id in ids }
        tabs.mapNotNull { it.engine.tmuxControl }.distinct().forEach(tmuxTabs::end)
        tabs.filter { it.engine.tmuxControl == null }.forEach { endTab(it.id) }
    }

    /**
     * Shows multiplexer session [sessionName] in the active tab, or selects the tab that
     * already shows it, so one session never gets two sets of tabs.
     */
    fun switchActiveMultiplexerSession(sessionName: String): Boolean {
        val tab = activeTab.value ?: return false
        if (tab.spec.multiplexerSessionName == sessionName) return true
        multiplexerSessions.tabsShowing(tab.spec, sessionName).firstOrNull()?.let { open ->
            selectTab(open.id)
            return true
        }
        if (tab.engine.tmuxControl != null) return tmuxTabs.switchSession(tab, sessionName)
        val type = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType
        MultiplexerRegistry.forType(type) ?: return false
        tab.spec =
            tab.spec.copy(multiplexer = type, multiplexerSessionName = sessionName, multiplexerCreateIfMissing = false)
        reconnectTab(tab)
        return true
    }

    fun reconnectTab(tab: TabSession) {
        tab.engine.tmuxControl?.spec = tab.spec
        // One key per tab, so parallel sessions to a host never share reconnect state.
        tab.engine.connect(tab.spec, "${tab.spec.sessionKey}:${tab.id}")
    }

    /**
     * Points the open tabs of an edited host at its new settings, so the next reconnect
     * uses them. Returns the tabs that are still running on the old settings.
     */
    fun applyHostUpdate(updated: TabSpec): List<TabSession> {
        val hostId = updated.hostId ?: return emptyList()
        return tabsForHost(hostId).filter { tab ->
            val next = tab.spec.withHostUpdate(updated)
            val stale = tab.spec.connectsDifferentlyThan(next) && tab.sessionState.value.status.isLive
            tab.spec = next
            stale
        }
    }

    /**
     * Reconnects the host's tabs that failed or dropped, so opening the host again retries
     * with its current settings instead of showing the last error.
     */
    fun reconnectIdleTabsForHost(hostId: Long) {
        reconnectTabs(tabsForHost(hostId).filterNot { it.sessionState.value.status.isLive })
    }

    /** Reconnects tabs after [applyHostUpdate], picking a multiplexer session where needed. */
    fun reconnectTabs(tabs: List<TabSession>) {
        // The tabs of one tmux control session share its connection.
        tabs.distinctBy { it.engine.tmuxControl ?: it }.forEach { tab ->
            val spec = tab.spec
            if (!spec.usesRuntimeMultiplexer || !spec.multiplexerSessionName.isNullOrBlank()) {
                reopenOrReconnect(tab)
                return@forEach
            }
            // The host just got a multiplexer, or a different one: find a session first.
            scope.launch {
                val name = runCatching { multiplexerSessions.resolveName(spec) }.getOrNull()
                if (_tabs.value.none { it.id == tab.id } || tab.spec != spec) return@launch
                tab.spec = if (name != null) spec.copy(multiplexerSessionName = name) else spec.copy(multiplexer = null)
                reopenOrReconnect(tab)
            }
        }
    }

    /** Reconnects [tab], or opens it again when it is or becomes a tmux control-mode tab, which reconnects as a group. */
    private fun reopenOrReconnect(tab: TabSession) {
        val control = tab.engine.tmuxControl
        if (control == null && !tab.spec.usesTmuxControlMode) {
            reconnectTab(tab)
            return
        }
        val spec = tab.spec
        if (control != null) tmuxTabs.end(control) else removeTab(tab)
        openTab(spec)
    }

    /** Closes every tab and any multiplexer check that is waiting on the user. */
    fun disconnect() {
        preflight.cancel()
        tmuxTabs.endAll()
        val tabs = _tabs.value
        _tabs.value = emptyList()
        _activeTabId.value = null
        tabs.forEach { tab -> fileTransfers.releaseTabThen(tab.id, tab.engine.sftp, tab.engine::dispose) }
    }

    /**
     * Runs [operation] on the tab's SFTP channel once the session is connected, and once more
     * if the session drops during the call and reconnects. Returns null for a closed tab or a
     * local shell.
     */
    suspend fun <T> withSftp(tabId: String, operation: suspend (SftpSession) -> T): T? {
        val tab = _tabs.value.firstOrNull { it.id == tabId && it.spec.transport != Transport.LocalShell } ?: return null
        return runWhenConnected(tab.sessionState) { operation(tab.engine.sftp) }
    }

    /** Answers the host key prompt: a multiplexer check's first, then the active tab's. */
    fun respondToHostKey(accepted: Boolean) {
        if (!preflight.respond(accepted)) activeTab.value?.engine?.respondToHostKey(accepted)
    }

    private fun publishTerminalClipboard(tabId: String, text: String) {
        val allowed = settingsRepository.remoteClipboardWriteEnabled.value &&
            attachedClients > 0 &&
            _activeTabId.value == tabId
        if (allowed) clipboard?.setPrimaryClip(plainTextClip("terminal clipboard", text, sensitive = true))
    }

    companion object {
        @Volatile private var instance: TerminalSessionRepository? = null

        fun getInstance(application: Application): TerminalSessionRepository = instance
            ?: synchronized(this) {
                instance ?: TerminalSessionRepository(application).also { instance = it }
            }
    }
}

private fun TabSession.isLiveFlow(): Flow<Boolean> = sessionState.map { it.status.isLive }.distinctUntilChanged()
