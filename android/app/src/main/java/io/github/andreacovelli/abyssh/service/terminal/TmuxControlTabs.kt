package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.CoroutineScope

/**
 * The tmux control sessions behind the open tabs, and the tabs each one shows: one per
 * tmux window. Runs on the main thread, as the repository does; [addTab], [removeTab],
 * and [selectTab] change the repository's tabs without touching tmux.
 */
internal class TmuxControlTabs(
    private val scope: CoroutineScope,
    private val hostKeyStore: HostKeyStore,
    private val localNetwork: LocalNetworkAccess,
    private val tabs: () -> List<TabSession>,
    private val addTab: (spec: TabSpec, control: TmuxControlSession, windowId: Int?, select: Boolean) -> TabSession,
    private val removeTab: (TabSession) -> Unit,
    private val selectTab: (String) -> Unit,
) : TmuxControlHost {
    private val sessions = mutableListOf<TmuxControlSession>()

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
        val control = TmuxControlSession(spec, scope, this, hostKeyStore, localNetwork)
        sessions += control
        val tab = addTab(spec, control, null, true)
        control.start()
        return tab
    }

    /** Opens a new window on the control session for [spec]'s host; returns false when there is none. */
    fun openWindow(spec: TabSpec): Boolean {
        val hostSessions = sessions.filter { it.spec.sessionKey == spec.sessionKey }
        val control = hostSessions.firstOrNull { it.sessionName == spec.multiplexerSessionName }
            ?: hostSessions.firstOrNull()
            ?: return false
        control.newWindow()
        return true
    }

    /** Shows tmux session [name] in the control session behind [tab]. */
    fun switchSession(tab: TabSession, name: String): Boolean {
        val control = tab.engine.tmuxControl ?: return false
        control.switchSession(name)
        return true
    }

    /**
     * After [tab] was removed: ends its window when other tabs still show the session,
     * or detaches from tmux when it was the last, which leaves the session running.
     */
    fun tabClosed(tab: TabSession, windowId: Int?) {
        val control = tab.engine.tmuxControl ?: return
        if (tabsOf(control).isEmpty()) {
            end(control)
        } else if (windowId != null) {
            control.killWindow(windowId)
        }
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

    override fun controlEnded(control: TmuxControlSession) {
        if (control in sessions) end(control)
    }

    override fun sessionRenamed(control: TmuxControlSession, name: String) {
        control.spec = control.spec.copy(multiplexerSessionName = name, multiplexerCreateIfMissing = false)
        tabsOf(control).forEach { it.spec = it.spec.copy(multiplexerSessionName = name, multiplexerCreateIfMissing = false) }
    }

    private fun tabsOf(control: TmuxControlSession): List<TabSession> = tabs().filter { it.engine.tmuxControl === control }
}
