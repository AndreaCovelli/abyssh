package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxLayout
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindow
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout

/** What [TmuxWindowBindings] asks of the tabs; the control session passes each call to the main thread. */
internal interface TmuxWindowTabs {
    fun open(windowId: Int, select: Boolean)

    fun close(view: TmuxPaneView)

    fun select(view: TmuxPaneView)

    /** Puts the session's tabs, which show [views], in that order, in the places they hold among the app's tabs. */
    fun order(views: List<TmuxPaneView>)

    /** Gives [view]'s tab [number], the number tmux gives its window. */
    fun number(view: TmuxPaneView, number: Int)
}

/** A tab's view, the tmux window it shows, and where that window's panes sit. */
internal class TmuxWindowBinding(val view: TmuxPaneView, var shown: Boolean) {
    var windowId: Int? = null
    var layout: TmuxWindowLayout? = null
    var activePane: Int? = null
    private var label: String? = null

    // The number the tab was last given, which tmux gave its window.
    private var number: Int? = null

    // The size each pane was last captured at.
    val captured = mutableMapOf<Int, TmuxPaneSize>()

    // Whether the tab has been hidden long enough to pause its panes, and the panes
    // this client paused for it.
    var quiet = false
    val paused = mutableSetOf<Int>()

    // Whether the tab's panes stay paused: it is quiet, or nothing has shown it yet.
    val held: Boolean get() = quiet || !shown

    fun shows(paneId: Int): Boolean = layout?.pane(paneId) != null

    /** Labels the tab with [window]'s number and name, and has [tabs] give it that number, when they changed. */
    fun nameAfter(window: TmuxWindow?, tabs: TmuxWindowTabs) {
        window ?: return
        if (window.label != label) {
            label = window.label
            view.nameWindow(window.label)
        }
        if (window.index != number) {
            number = window.index
            tabs.number(view, window.index)
        }
    }
}

/**
 * Which tab shows which tmux window, and where the window's panes sit. Keeps the tabs in
 * step with the session's windows: closes tabs whose window is gone, asks [tabs] to
 * open one for each new window, labels, numbers, and orders them as tmux numbers the
 * windows, and fills each pane in from `capture-pane` when it appears or its size changes. The panes of a quiet tab, and of a tab nothing has shown
 * yet, are paused, so tmux sends nothing for them, and captured once the tab is shown:
 * attaching to a session with many windows downloads only the windows the user looks
 * at. When only a pane's height changes, as when the keyboard shows or hides, its
 * terminal keeps the history and only the screens are captured. Every call runs on the
 * control session's thread, which [onThread] runs a block on; commands go out only
 * while [attached] says tmux has reported the session.
 */
internal class TmuxWindowBindings(
    private val link: TmuxCommandSink,
    private val tabs: TmuxWindowTabs,
    private val attached: () -> Boolean,
    private val onThread: (() -> Unit) -> Unit,
) {
    private val bindings = mutableListOf<TmuxWindowBinding>()
    private var windows: Map<Int, TmuxWindow> = emptyMap()

    // Windows whose tab is on its way, and windows this client created and wants to show.
    private val opening = mutableSetOf<Int>()
    private val requested = mutableSetOf<Int>()

    private val captures = TmuxPaneCaptures(link, bindingOf = { paneId -> bindings.firstOrNull { it.shows(paneId) } }, onThread)

    // The view whose tab is the app's active one, if it belongs to this session.
    private var focused: TmuxPaneView? = null

    // The order the tabs were last put in.
    private var order: List<TmuxPaneView> = emptyList()

    /** Whether the session has tabs and every one of them is quiet. */
    val allQuiet: Boolean get() = bindings.isNotEmpty() && bindings.all { it.quiet }

    /**
     * Binds [view] to window [windowId], or to a window that has no tab yet when null.
     * Unless [shown], its panes wait for [show] before they are captured.
     */
    fun attach(view: TmuxPaneView, windowId: Int?, shown: Boolean) {
        val binding = TmuxWindowBinding(view, shown)
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
        if (window == null) {
            listWindows()
        } else {
            orderTabs()
            showLayout(binding, window.layout, window.paneId)
        }
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

    /**
     * Makes the active tab's window tmux's current one, which tmux then sizes to this client.
     * tmux keeps one current window per session, shared by every client attached to it, so
     * another client on the same session follows along, as it would after `select-window`
     * typed there.
     */
    fun selectFocused() {
        if (!attached()) return
        val windowId = bindings.firstOrNull { it.view === focused }?.windowId ?: return
        link.send(TmuxControlCommands.selectWindow(windowId))
    }

    /** The window [view] shows, and its active pane, or null before tmux reported them. */
    fun windowOf(view: TmuxPaneView): Pair<Int, Int>? {
        val binding = bindings.firstOrNull { it.view === view } ?: return null
        return (binding.windowId ?: return null) to (binding.activePane ?: return null)
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
        changeHold(binding) { binding.quiet = quiet }
    }

    /** Something showed [view]'s tab for the first time: its panes resume and are captured. */
    fun show(view: TmuxPaneView) {
        val binding = bindings.firstOrNull { it.view === view } ?: return
        if (binding.shown) return
        changeHold(binding) { binding.shown = true }
    }

    /** Runs [change], then pauses the binding's panes if it holds them now, or resumes and captures them if it let them go. */
    private fun changeHold(binding: TmuxWindowBinding, change: () -> Unit) {
        val wasHeld = binding.held
        change()
        if (binding.held == wasHeld) return
        val layout = binding.layout ?: return
        val activePane = binding.activePane ?: return
        if (binding.held) {
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

    /**
     * tmux left control mode. A tab that stays open takes whichever window is the
     * active one if it attaches again, since the window it showed may be gone by then.
     */
    fun forgetWindows() {
        forgetPanes()
        bindings.forEach { it.windowId = null }
        windows = emptyMap()
        opening.clear()
        requested.clear()
        order = emptyList()
    }

    /** Window [windowId] is now called [name]. */
    fun windowRenamed(windowId: Int, name: String) {
        val window = windows[windowId] ?: return
        windows = windows + (windowId to window.copy(name = name))
        bindings.filter { it.windowId == windowId }.forEach { it.nameAfter(windows[windowId], tabs) }
    }

    /** After a reconnect, every pane is captured again. */
    fun forgetPanes() {
        bindings.forEach { binding ->
            binding.layout = null
            binding.activePane = null
            binding.captured.clear()
            binding.paused.clear()
        }
        captures.clear()
    }

    fun output(paneId: Int, data: ByteArray) {
        if (captures.pending(paneId)) return
        bindings.firstOrNull { it.shows(paneId) }?.view?.paneOutput(paneId, data)
    }

    /**
     * tmux paused [paneId], dropping its output, so the pane is captured again after it
     * continues. A held tab's pane stays paused, as when this client paused it.
     */
    fun paused(paneId: Int) {
        val binding = bindings.firstOrNull { it.shows(paneId) }
        if (binding?.held == true) {
            binding.paused += paneId
            return
        }
        link.send(TmuxControlCommands.continuePane(paneId))
        if (binding != null) captures.seed(paneId)
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

    /**
     * Matches the tabs to the session's [list] of windows, in window order: closes,
     * binds, opens, and captures as needed, and orders the tabs as the windows are.
     */
    private fun applyWindows(list: List<TmuxWindow>) {
        windows = list.associateBy { it.id }
        bindings.filter { binding -> binding.windowId.let { it != null && it !in windows } }.forEach { binding ->
            bindings -= binding
            tabs.close(binding.view)
        }
        val free = list.filter { window -> bindings.none { it.windowId == window.id } && window.id !in opening }
            .toMutableList()
        bindings.filter { it.windowId == null }.forEach { binding ->
            // A tab without a window, as on the first attach, shows the window tmux shows.
            val window = free.firstOrNull { it.active } ?: free.firstOrNull()
            window?.let {
                free -= it
                bindTo(binding, it.id)
            }
        }
        // With every tab gone, as after switching sessions, the active window's tab takes over.
        val replacing = bindings.isEmpty()
        free.forEach { window ->
            opening += window.id
            tabs.open(window.id, select = requested.remove(window.id) || (replacing && window.active))
        }
        bindings.forEach { it.nameAfter(windows[it.windowId], tabs) }
        orderTabs()
        bindings.forEach { binding -> windows[binding.windowId]?.let { showLayout(binding, it.layout, it.paneId) } }
    }

    private fun bindTo(binding: TmuxWindowBinding, windowId: Int) {
        binding.windowId = windowId
        binding.view.bindWindow(windowId)
        binding.nameAfter(windows[windowId], tabs)
        if (binding.view === focused) selectFocused()
    }

    /** Puts the tabs in the order of their windows' numbers, when that changed. */
    private fun orderTabs() {
        val sorted = bindings.mapNotNull { binding -> windows[binding.windowId]?.let { it.index to binding.view } }
            .sortedBy { it.first }
            .map { it.second }
        if (sorted.size < 2 || sorted == order) return
        order = sorted
        tabs.order(sorted)
    }

    /** Shows [layout] in the binding's tab, and captures each pane that is new or changed size. */
    private fun showLayout(binding: TmuxWindowBinding, layout: TmuxWindowLayout, activePane: Int) {
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
            if (binding.held) {
                pause(binding, pane.paneId)
            } else if (binding.captured[pane.paneId] != pane.size) {
                val old = binding.captured.put(pane.paneId, pane.size)
                // A pane whose full capture hasn't landed has no terminal to keep yet.
                val kept = old != null && old.cols == pane.size.cols && !captures.pendingFull(pane.paneId)
                if (kept) captures.resize(pane.paneId, old, pane.size) else captures.seed(pane.paneId)
            }
        }
    }

    /** Pauses pane [paneId] of [binding]'s held tab, unless it already is. */
    private fun pause(binding: TmuxWindowBinding, paneId: Int) {
        if (!attached() || !binding.paused.add(paneId)) return
        link.send(TmuxControlCommands.pausePane(paneId))
    }

    /** Has tmux send the output of [binding]'s paused [paneIds] again. */
    private fun resume(binding: TmuxWindowBinding, paneIds: Set<Int>) {
        val resumed = paneIds.toList()
        binding.paused -= resumed
        if (attached()) resumed.forEach { link.send(TmuxControlCommands.continuePane(it)) }
    }
}
