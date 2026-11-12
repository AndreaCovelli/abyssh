package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.network.NetworkWaiter
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.CoroutineScope

/**
 * The tmux control sessions behind the open tabs, and the tabs each one shows: one per
 * tmux window. Runs on the main thread, as the repository does; [addTab], [removeTab],
 * and [selectTab] change the repository's tabs without touching tmux. [sessionsChanged]
 * hears when a control session reports that its server's sessions changed.
 */
internal class TmuxControlTabs(
    private val scope: CoroutineScope,
    private val hostKeyStore: HostKeyStore,
    private val localNetwork: LocalNetworkAccess,
    private val network: NetworkWaiter,
    private val tabs: () -> List<TabSession>,
    private val addTab: (spec: TabSpec, control: TmuxControlSession, windowId: Int?, select: Boolean) -> TabSession,
    private val removeTab: (TabSession) -> Unit,
    private val selectTab: (String) -> Unit,
    private val orderTabs: (List<TabSession>) -> Unit,
    private val sessionsChanged: (TabSpec) -> Unit,
) : TmuxControlHost {
    private val sessions = mutableListOf<TmuxControlSession>()
    private var activeTab: TabSession? = null

    /**
     * Opens a tab on the control session for [spec]'s host and tmux session, starting
     * one when there is none; the session opens a tab for each of its other windows.
     */
    fun open(spec: TabSpec): TabSession {
        val running = sessions.firstOrNull {
            it.spec.sessionKey == spec.sessionKey && it.sessionName == spec.multiplexerSessionName
        }
        if (running != null) {
            tabsOf(running).firstOrNull()?.let { tab ->
                selectTab(tab.id)
                return tab
            }
        }
        val control = TmuxControlSession(spec, scope, this, hostKeyStore, localNetwork, network)
        sessions += control
        val tab = addTab(spec, control, null, true)
        control.start()
        return tab
    }

    /** What [openWindow] did. */
    enum class OpenWindowResult {
        Opened,

        /** The control session is reconnecting; tmux would never hear of the window. */
        Offline,

        /** No control session runs on the host. */
        NoSession,
    }

    /** Opens a new window on the control session for [spec]'s host, if one is attached. */
    fun openWindow(spec: TabSpec): OpenWindowResult {
        val hostSessions = sessions.filter { it.spec.sessionKey == spec.sessionKey }
        val control = hostSessions.firstOrNull { it.sessionName == spec.multiplexerSessionName }
            ?: hostSessions.firstOrNull()
            ?: return OpenWindowResult.NoSession
        if (control.status.value.status != SessionStatus.Connected) return OpenWindowResult.Offline
        control.windowControl.newWindow()
        return OpenWindowResult.Opened
    }

    /**
     * A connected control session on [spec]'s host, preferring the one that shows
     * [spec]'s tmux session. All of them talk to the same tmux server.
     */
    fun connectedTo(spec: TabSpec): TmuxControlSession? {
        val hostSessions = sessions.filter {
            it.spec.sessionKey == spec.sessionKey && it.status.value.status == SessionStatus.Connected
        }
        return hostSessions.firstOrNull { it.sessionName == spec.multiplexerSessionName } ?: hostSessions.firstOrNull()
    }

    /**
     * Runs [action] on [tab]'s tmux window. Returns false when [tab] shows no tmux
     * window, or its control session isn't attached, so tmux would never hear of it.
     */
    fun run(tab: TabSession, action: TmuxWindowAction): Boolean {
        val control = tab.engine.tmuxControl ?: return false
        val view = tab.engine.tmuxView ?: return false
        if (control.status.value.status != SessionStatus.Connected) return false
        when (action) {
            TmuxWindowAction.SplitRight -> control.windowControl.splitPane(view, sideBySide = true)
            TmuxWindowAction.SplitDown -> control.windowControl.splitPane(view, sideBySide = false)
            TmuxWindowAction.ClosePane -> control.windowControl.killPane(view)
            TmuxWindowAction.ZoomPane -> control.windowControl.toggleZoom(view)
            is TmuxWindowAction.Rename -> control.windowControl.renameWindow(view, action.name)
            TmuxWindowAction.NextWindow -> selectAdjacentWindow(control, tab, 1)
            TmuxWindowAction.PreviousWindow -> selectAdjacentWindow(control, tab, -1)
            TmuxWindowAction.Detach -> end(control)
        }
        return true
    }

    // The tabs are in window order, so the next tab shows the next window.
    private fun selectAdjacentWindow(control: TmuxControlSession, tab: TabSession, delta: Int) {
        val tabs = tabsOf(control)
        val index = tabs.indexOf(tab)
        if (index < 0 || tabs.size < 2) return
        selectTab(tabs[Math.floorMod(index + delta, tabs.size)].id)
    }

    /** Shows tmux session [name] in the control session behind [tab]. */
    fun switchSession(tab: TabSession, name: String): Boolean {
        val control = tab.engine.tmuxControl ?: return false
        control.switchSession(name)
        return true
    }

    /** Closes [control]'s tabs and detaches from tmux. */
    fun end(control: TmuxControlSession) {
        sessions -= control
        tabsOf(control).forEach(removeTab)
        control.dispose()
    }

    fun endAll() {
        sessions.toList().forEach(::end)
    }

    /** Tells each control session which of its tabs, if any, is now the active one. */
    fun activeTabChanged(tab: TabSession?) {
        activeTab = tab
        sessions.forEach { control ->
            control.focus(tab?.engine?.takeIf { it.tmuxControl === control }?.tmuxView)
        }
    }

    override fun openWindowTab(control: TmuxControlSession, windowId: Int, select: Boolean) {
        if (control !in sessions) return
        val spec = control.spec.copy(multiplexerSessionName = control.sessionName, multiplexerCreateIfMissing = false)
        addTab(spec, control, windowId, select)
    }

    // The session may get windows again, as after switching sessions, so it stays open
    // without tabs; tmux ends control mode when the session itself ends.
    override fun closeWindowTab(control: TmuxControlSession, view: TmuxPaneView) {
        tabsOf(control).firstOrNull { it.engine.showsTmuxView(view) }?.let(removeTab)
    }

    override fun selectWindowTab(control: TmuxControlSession, view: TmuxPaneView) {
        tabsOf(control).firstOrNull { it.engine.showsTmuxView(view) }?.let { selectTab(it.id) }
    }

    override fun orderWindowTabs(control: TmuxControlSession, views: List<TmuxPaneView>) {
        val tabs = tabsOf(control)
        orderTabs(views.mapNotNull { view -> tabs.firstOrNull { it.engine.showsTmuxView(view) } })
    }

    // One tab stays, the active one if it is the session's, to say why and offer to attach
    // again, which reopens the other windows.
    override fun controlEnded(control: TmuxControlSession) {
        if (control !in sessions) return
        val tabs = tabsOf(control)
        val kept = tabs.firstOrNull { it === activeTab } ?: tabs.firstOrNull()
        if (kept == null) {
            end(control)
            return
        }
        tabs.filter { it !== kept }.forEach(removeTab)
    }

    override fun sessionRenamed(control: TmuxControlSession, name: String) {
        control.spec = control.spec.copy(multiplexerSessionName = name, multiplexerCreateIfMissing = false)
        tabsOf(control).forEach { it.spec = it.spec.copy(multiplexerSessionName = name, multiplexerCreateIfMissing = false) }
    }

    override fun sessionsChanged(control: TmuxControlSession) {
        if (control in sessions) sessionsChanged(control.spec)
    }

    private fun tabsOf(control: TmuxControlSession): List<TabSession> = tabs().filter { it.engine.tmuxControl === control }
}
