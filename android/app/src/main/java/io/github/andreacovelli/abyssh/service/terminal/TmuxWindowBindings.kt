package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxLayout
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSeed
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneState
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindow
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout

/** What [TmuxWindowBindings] asks of the tabs; the control session passes each call to the main thread. */
internal interface TmuxWindowTabs {
    fun open(windowId: Int, select: Boolean)

    fun close(view: TmuxPaneView)

    fun select(view: TmuxPaneView)
}

/**
 * Which tab shows which tmux window, and where the window's panes sit. Keeps the tabs in
 * step with the session's windows: closes tabs whose window is gone, asks [tabs] to
 * open one for each new window, and fills each pane in from `capture-pane` when it
 * appears or its size changes. A quiet tab's panes are paused, so tmux sends nothing
 * for them, and captured again once the tab is shown. Every call runs on the control
 * session's thread; commands go out only while [attached] says tmux has reported the
 * session.
 */
internal class TmuxWindowBindings(
    private val link: TmuxControlLink,
    private val tabs: TmuxWindowTabs,
    private val attached: () -> Boolean,
) {
    private class Binding(val view: TmuxPaneView) {
        var windowId: Int? = null
        var layout: TmuxWindowLayout? = null
        var activePane: Int? = null

        // The size each pane was last captured at.
        val captured = mutableMapOf<Int, TmuxPaneSize>()

        // Whether the tab has been hidden long enough to pause its panes, and the panes
        // this client paused for it.
        var quiet = false
        val paused = mutableSetOf<Int>()

        fun shows(paneId: Int): Boolean = layout?.pane(paneId) != null
    }

    private val bindings = mutableListOf<Binding>()
    private var windows: Map<Int, TmuxWindow> = emptyMap()

    // Windows whose tab is on its way, and windows this client created and wants to show.
    private val opening = mutableSetOf<Int>()
    private val requested = mutableSetOf<Int>()

    // Panes being captured, by capture number; their output is dropped until the capture lands.
    private val seeding = mutableMapOf<Int, Long>()
    private var seedCount = 0L

    // The view whose tab is the app's active one, if it belongs to this session.
    private var focused: TmuxPaneView? = null

    /** Binds [view] to window [windowId], or to a window that has no tab yet when null. */
    fun attach(view: TmuxPaneView, windowId: Int?) {
        val binding = Binding(view)
        bindings += binding
        if (windowId == null) {
            if (attached()) listWindows()
            return
        }
        opening -= windowId
        bindTo(binding, windowId)
        if (!attached()) return
        // A window missing from the last list may be new; listing again closes the tab if it is gone.
        val window = windows[windowId]
        if (window == null) listWindows() else showLayout(binding, window.layout, window.paneId)
    }

    fun detach(view: TmuxPaneView) {
        bindings.filter { it.view === view }.forEach { binding ->
            bindings -= binding
            // The window may get another tab, which must receive its output.
            resume(binding, binding.paused)
        }
        if (focused === view) focused = null
    }

    /** [view]'s tab is now the active one, or none of this session's tabs is when null. */
    fun focus(view: TmuxPaneView?) {
        focused = view
        selectFocused()
    }

    /** Makes the active tab's window tmux's current one, which tmux then sizes to this client. */
    fun selectFocused() {
        if (!attached()) return
        val windowId = bindings.firstOrNull { it.view === focused }?.windowId ?: return
        link.send(TmuxControlCommands.selectWindow(windowId))
    }

    /** Whether pane [paneId] is in the window [view] shows. */
    fun shows(view: TmuxPaneView, paneId: Int): Boolean = bindings.any { it.view === view && it.shows(paneId) }

    /** The user picked pane [paneId] in [view]'s window; tmux reports the change back, which then changes nothing. */
    fun selectPane(view: TmuxPaneView, paneId: Int) {
        val binding = bindings.firstOrNull { it.view === view && it.shows(paneId) } ?: return
        binding.activePane = paneId
        link.send(TmuxControlCommands.selectPane(paneId))
    }

    /**
     * Pauses the panes of [view]'s window while [quiet], so tmux stops sending their
     * output, and resumes and captures them again once it isn't.
     */
    fun setQuiet(view: TmuxPaneView, quiet: Boolean) {
        val binding = bindings.firstOrNull { it.view === view } ?: return
        if (binding.quiet == quiet) return
        binding.quiet = quiet
        val layout = binding.layout ?: return
        val activePane = binding.activePane ?: return
        if (quiet) {
            layout.panes.forEach { pause(binding, it.paneId) }
            return
        }
        if (!attached()) return
        binding.captured -= binding.paused
        resume(binding, binding.paused)
        showLayout(binding, layout, activePane)
    }

    /** This client created window [windowId]: its tab becomes the active one. */
    fun created(windowId: Int) {
        val bound = bindings.firstOrNull { it.windowId == windowId }
        if (bound != null) tabs.select(bound.view) else requested += windowId
    }

    /** After a reconnect, every pane is captured again. */
    fun forgetPanes() {
        bindings.forEach { binding ->
            binding.layout = null
            binding.activePane = null
            binding.captured.clear()
            binding.paused.clear()
        }
        seeding.clear()
    }

    fun output(paneId: Int, data: ByteArray) {
        if (paneId in seeding) return
        bindings.firstOrNull { it.shows(paneId) }?.view?.paneOutput(paneId, data)
    }

    /**
     * tmux paused [paneId], dropping its output, so the pane is captured again after it
     * continues. A quiet tab's pane stays paused, as when this client paused it.
     */
    fun paused(paneId: Int) {
        val binding = bindings.firstOrNull { it.shows(paneId) }
        if (binding?.quiet == true) {
            binding.paused += paneId
            return
        }
        link.send(TmuxControlCommands.continuePane(paneId))
        if (binding != null) seed(paneId)
    }

    fun layoutChanged(windowId: Int, layout: String) {
        val binding = bindings.firstOrNull { it.windowId == windowId } ?: return
        val parsed = TmuxLayout.parse(layout)
        // A pane that was split off becomes the active one, which only a new list reports.
        val activePane = binding.activePane?.takeIf { parsed?.pane(it) != null }
        if (parsed == null || activePane == null) {
            listWindows()
            return
        }
        windows[windowId]?.let { windows = windows + (windowId to it.copy(layout = parsed)) }
        showLayout(binding, parsed, activePane)
    }

    fun listWindows() {
        link.send(TmuxControlCommands.LIST_WINDOWS) { reply ->
            if (!reply.failed) applyWindows(TmuxControlCommands.parseWindows(reply.lines))
        }
    }

    /** Matches the tabs to the session's [list] of windows: closes, binds, opens, and captures as needed. */
    private fun applyWindows(list: List<TmuxWindow>) {
        windows = list.associateBy { it.id }
        bindings.filter { binding -> binding.windowId.let { it != null && it !in windows } }.forEach { binding ->
            bindings -= binding
            tabs.close(binding.view)
        }
        val free = list.filter { window -> bindings.none { it.windowId == window.id } && window.id !in opening }
            .sortedByDescending { it.active }
            .toMutableList()
        bindings.filter { it.windowId == null }.forEach { binding ->
            free.removeFirstOrNull()?.let { bindTo(binding, it.id) }
        }
        // With every tab gone, as after switching sessions, the active window's tab takes over.
        val replacing = bindings.isEmpty()
        free.forEach { window ->
            opening += window.id
            tabs.open(window.id, select = requested.remove(window.id) || (replacing && window.active))
        }
        bindings.forEach { binding -> windows[binding.windowId]?.let { showLayout(binding, it.layout, it.paneId) } }
    }

    private fun bindTo(binding: Binding, windowId: Int) {
        binding.windowId = windowId
        binding.view.bindWindow(windowId)
        if (binding.view === focused) selectFocused()
    }

    /** Shows [layout] in the binding's tab, and captures each pane that is new or changed size. */
    private fun showLayout(binding: Binding, layout: TmuxWindowLayout, activePane: Int) {
        if (layout != binding.layout || activePane != binding.activePane) {
            binding.layout = layout
            binding.activePane = activePane
            binding.view.showLayout(layout, activePane)
        }
        val paneIds = layout.panes.map { it.paneId }.toSet()
        binding.captured.keys.retainAll(paneIds)
        // A pane moved to another window must send its output there.
        resume(binding, binding.paused - paneIds)
        layout.panes.forEach { pane ->
            if (binding.quiet) {
                pause(binding, pane.paneId)
            } else if (binding.captured[pane.paneId] != pane.size) {
                binding.captured[pane.paneId] = pane.size
                seed(pane.paneId)
            }
        }
    }

    /** Pauses pane [paneId] of [binding]'s quiet tab, unless it already is. */
    private fun pause(binding: Binding, paneId: Int) {
        if (!attached() || !binding.paused.add(paneId)) return
        link.send(TmuxControlCommands.pausePane(paneId))
    }

    /** Has tmux send the output of [binding]'s paused [paneIds] again. */
    private fun resume(binding: Binding, paneIds: Set<Int>) {
        val resumed = paneIds.toList()
        binding.paused -= resumed
        if (attached()) resumed.forEach { link.send(TmuxControlCommands.continuePane(it)) }
    }

    /**
     * Captures pane [paneId] and shows it; the pane's output waits until then, since the
     * capture includes it. A resize captures again too: when tmux resizes a pane it
     * reflows the lines and moves lines between the history and the screen in its own
     * way, which resizing the local terminal can't match.
     */
    private fun seed(paneId: Int) {
        val number = ++seedCount
        seeding[paneId] = number
        link.sendAll(TmuxControlCommands.capturePane(paneId)) { replies ->
            if (seeding[paneId] != number) return@sendAll
            seeding -= paneId
            val target = bindings.firstOrNull { it.shows(paneId) } ?: return@sendAll
            val state = replies[0].takeUnless { it.failed }?.lines?.firstOrNull()?.let(TmuxPaneState::parse)
                ?: return@sendAll
            target.captured[paneId] = state.size
            target.view.showPane(paneId, state.size, TmuxPaneSeed.build(state, replies[1].lines, replies[2].lines, replies[3].lines))
        }
    }
}
