package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxLayout
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSeed
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneState
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindow

/** What [TmuxWindowBindings] asks of the tabs; the control session passes each call to the main thread. */
internal interface TmuxWindowTabs {
    fun open(windowId: Int, select: Boolean)

    fun close(view: TmuxPaneView)

    fun select(view: TmuxPaneView)
}

/**
 * Which tab shows which tmux window, and which pane each one shows. Keeps the tabs in
 * step with the session's windows: closes tabs whose window is gone, asks [tabs] to
 * open one for each new window, and fills each tab in from `capture-pane` when its pane
 * or the pane's size changes. Every call runs on the control session's thread; commands
 * go out only while [attached] says tmux has reported the session.
 */
internal class TmuxWindowBindings(
    private val link: TmuxControlLink,
    private val tabs: TmuxWindowTabs,
    private val attached: () -> Boolean,
) {
    private class Binding(val view: TmuxPaneView) {
        var windowId: Int? = null
        var paneId: Int? = null
        var size: TmuxPaneSize? = null
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
        if (window == null) listWindows() else updatePane(binding, window)
    }

    fun detach(view: TmuxPaneView) {
        bindings.removeAll { it.view === view }
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

    /** The pane [view] shows, once it has one. */
    fun paneOf(view: TmuxPaneView): Int? = bindings.firstOrNull { it.view === view }?.paneId

    /** This client created window [windowId]: its tab becomes the active one. */
    fun created(windowId: Int) {
        val bound = bindings.firstOrNull { it.windowId == windowId }
        if (bound != null) tabs.select(bound.view) else requested += windowId
    }

    /** After a reconnect, every pane is captured again. */
    fun forgetPanes() {
        bindings.forEach { it.paneId = null }
        seeding.clear()
    }

    fun output(paneId: Int, data: ByteArray) {
        if (paneId in seeding) return
        bindings.firstOrNull { it.paneId == paneId }?.view?.paneOutput(data)
    }

    /** tmux paused [paneId], dropping its output, so the pane is captured again after it continues. */
    fun paused(paneId: Int) {
        link.send(TmuxControlCommands.continuePane(paneId))
        bindings.filter { it.paneId == paneId }.forEach(::seed)
    }

    fun layoutChanged(windowId: Int, layout: String) {
        val binding = bindings.firstOrNull { it.windowId == windowId } ?: return
        val size = binding.paneId?.let { TmuxLayout.paneSizes(layout)[it] }
        if (size == null) {
            listWindows()
            return
        }
        windows[windowId]?.let { windows = windows + (windowId to it.copy(size = size)) }
        if (size != binding.size) {
            binding.size = size
            seed(binding)
        }
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
        bindings.forEach { binding -> windows[binding.windowId]?.let { updatePane(binding, it) } }
    }

    private fun bindTo(binding: Binding, windowId: Int) {
        binding.windowId = windowId
        binding.view.bindWindow(windowId)
        if (binding.view === focused) selectFocused()
    }

    private fun updatePane(binding: Binding, window: TmuxWindow) {
        if (binding.paneId != window.paneId || binding.size != window.size) {
            binding.paneId = window.paneId
            binding.size = window.size
            seed(binding)
        }
    }

    /**
     * Captures the binding's pane and shows it; the pane's output waits until then, since
     * the capture includes it. A resize captures again too: when tmux resizes a pane it
     * reflows the lines and moves lines between the history and the screen in its own
     * way, which resizing the local terminal can't match.
     */
    private fun seed(binding: Binding) {
        val paneId = binding.paneId ?: return
        val number = ++seedCount
        seeding[paneId] = number
        link.sendAll(TmuxControlCommands.capturePane(paneId)) { replies ->
            if (seeding[paneId] != number) return@sendAll
            seeding -= paneId
            val target = bindings.firstOrNull { it.paneId == paneId } ?: return@sendAll
            val state = replies[0].takeUnless { it.failed }?.lines?.firstOrNull()?.let(TmuxPaneState::parse)
                ?: return@sendAll
            target.size = state.size
            target.view.showPane(state.size, TmuxPaneSeed.build(state, replies[1].lines, replies[2].lines, replies[3].lines))
        }
    }
}
