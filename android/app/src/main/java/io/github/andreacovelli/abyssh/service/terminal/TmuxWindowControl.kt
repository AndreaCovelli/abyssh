package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands

/**
 * The commands a control session sends about its windows and their panes. Each runs on
 * the session's thread through [onThread], and goes out only while [attached] says tmux
 * has reported the session.
 */
internal class TmuxWindowControl(
    private val link: TmuxCommandSink,
    private val windows: TmuxWindowBindings,
    private val attached: () -> Boolean,
    private val onThread: (() -> Unit) -> Unit,
) {
    /** Creates a window in the session; its tab opens and becomes the active one. */
    fun newWindow() = onThread {
        if (!attached()) return@onThread
        link.send(TmuxControlCommands.NEW_WINDOW) { reply ->
            TmuxControlCommands.parseNewWindow(reply.lines)?.let(windows::created)
        }
    }

    /** Splits the active pane of [view]'s window, side by side when [sideBySide], else one above the other. */
    fun splitPane(view: TmuxPaneView, sideBySide: Boolean) = onActivePane(view) { TmuxControlCommands.splitPane(it, sideBySide) }

    /** Kills the active pane of [view]'s window, and the window with it when that was its only pane. */
    fun killPane(view: TmuxPaneView) = onActivePane(view, TmuxControlCommands::killPane)

    /** Zooms the active pane of [view]'s window to fill it, or unzooms it. */
    fun toggleZoom(view: TmuxPaneView) = onActivePane(view, TmuxControlCommands::toggleZoom)

    fun renameWindow(view: TmuxPaneView, name: String) = onThread {
        val (windowId, _) = windows.windowOf(view) ?: return@onThread
        if (attached() && name.isNotBlank()) link.send(TmuxControlCommands.renameWindow(windowId, name))
    }

    private fun onActivePane(view: TmuxPaneView, command: (paneId: Int) -> String) = onThread {
        val (_, paneId) = windows.windowOf(view) ?: return@onThread
        if (attached()) link.send(command(paneId))
    }
}
