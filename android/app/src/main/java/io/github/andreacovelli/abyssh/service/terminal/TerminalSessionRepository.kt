package io.github.andreacovelli.abyssh.service.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CONNECT_WAIT_MS = 20_000L

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
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val preflight = MultiplexerPreflight(hostKeyStore)
    private val foreground = SessionForegroundTracker(appContext)
    private var attachedClients = 0

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

    init {
        scope.launch {
            combine(liveTabs, activeTab) { live, active ->
                if (live.isEmpty()) null else sessionNotificationLabel(_tabs.value.map { it.spec }, active?.spec)
            }.collect(foreground::update)
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

    suspend fun resolveMultiplexerSessionName(spec: TabSpec, reuseDetachedAbysshSession: Boolean = false): String {
        val localNames = openMultiplexerSessionNames(spec)
        return preflight.run { probe -> probe.resolveSessionName(spec, localNames, reuseDetachedAbysshSession) }
    }

    suspend fun listMultiplexerSessions(spec: TabSpec): List<RemoteMultiplexerSession> =
        preflight.run { probe -> probe.listSessions(spec) }

    fun openTab(spec: TabSpec): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                publishClipboardText = { text -> publishTerminalClipboard(id, text) },
                scope = scope,
                localShellService = NativeLocalShellService(appContext.filesDir, appContext.cacheDir),
                hostKeyStore = hostKeyStore,
                onShellExited = { scope.launch { closeTab(id) } },
            )
        val tab = TabSession(id, spec, engine)
        _tabs.value += tab
        _activeTabId.value = id
        reconnectTab(tab)
        return tab
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun closeTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val nextSameHost = remaining.firstOrNull { it.spec.hostId == tab.spec.hostId }
            _activeTabId.value = nextSameHost?.id ?: remaining.firstOrNull()?.id
        }
        tab.engine.dispose()
    }

    fun reconnectActive() {
        activeTab.value?.let(::reconnectTab)
    }

    fun switchActiveMultiplexerSession(sessionName: String): Boolean {
        val tab = activeTab.value ?: return false
        val type = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType
        MultiplexerRegistry.forType(type) ?: return false
        tab.spec =
            tab.spec.copy(multiplexer = type, multiplexerSessionName = sessionName, multiplexerCreateIfMissing = false)
        reconnectTab(tab)
        return true
    }

    fun reconnectTab(tab: TabSession) {
        val key = if (tab.spec.transport == Transport.LocalShell) "local-shell:${tab.id}" else tab.spec.sessionKey
        tab.engine.connect(tab.spec, key)
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
        tabs.forEach { tab ->
            val spec = tab.spec
            if (!spec.usesRuntimeMultiplexer || !spec.multiplexerSessionName.isNullOrBlank()) {
                reconnectTab(tab)
                return@forEach
            }
            // The host just got a multiplexer, or a different one: find a session first.
            scope.launch {
                val name = runCatching { resolveMultiplexerSessionName(spec) }.getOrNull()
                if (_tabs.value.none { it.id == tab.id } || tab.spec != spec) return@launch
                tab.spec = if (name != null) spec.copy(multiplexerSessionName = name) else spec.copy(multiplexer = null)
                reconnectTab(tab)
            }
        }
    }

    /** Closes every tab and any multiplexer check that is waiting on the user. */
    fun disconnect() {
        preflight.cancel()
        val tabs = _tabs.value
        _tabs.value = emptyList()
        _activeTabId.value = null
        tabs.forEach { it.engine.dispose() }
    }

    suspend fun awaitTabConnected(tabId: String, timeoutMs: Long = CONNECT_WAIT_MS) {
        val tab = _tabs.value.firstOrNull { it.id == tabId && it.spec.transport != Transport.LocalShell } ?: return
        if (tab.sessionState.value.status == SessionStatus.Connected) return
        withTimeoutOrNull(timeoutMs) {
            tab.sessionState.first { it.status == SessionStatus.Connected }
        } ?: throw UiTextException(
            UiText.Resource(R.string.error_reconnect_timeout),
            "Timed out waiting for the session to reconnect",
        )
    }

    /** Answers the host key prompt: a multiplexer check's first, then the active tab's. */
    fun respondToHostKey(accepted: Boolean) {
        if (!preflight.respond(accepted)) activeTab.value?.engine?.respondToHostKey(accepted)
    }

    private fun openMultiplexerSessionNames(spec: TabSpec): List<String> {
        val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
        return tabsForHost(spec.hostId)
            .filter { it.spec.multiplexer == type }
            .mapNotNull { it.spec.multiplexerSessionName }
    }

    private fun publishTerminalClipboard(tabId: String, text: String) {
        val allowed = settingsRepository.remoteClipboardWriteEnabled.value &&
            attachedClients > 0 &&
            _activeTabId.value == tabId
        if (allowed) clipboard?.setPrimaryClip(ClipData.newPlainText("terminal clipboard", text))
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
