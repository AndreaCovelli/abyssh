package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlEvent
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneResize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSeed
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneState

/**
 * Captures panes with `capture-pane` and shows each capture in the tab that shows the
 * pane, which [bindingOf] finds. A pane's output is dropped while a capture of it is on
 * its way, since the capture includes it. Runs on the control session's thread, which
 * [onThread] runs a block on.
 */
internal class TmuxPaneCaptures(
    private val link: TmuxCommandSink,
    private val bindingOf: (paneId: Int) -> TmuxWindowBinding?,
    private val onThread: (() -> Unit) -> Unit,
) {
    // A capture on its way; [full] when it reads the history too.
    private class Capture(val full: Boolean)

    // Panes being captured, and the latest capture of each.
    private val seeding = mutableMapOf<Int, Capture>()

    /** Whether a capture of [paneId] is on its way, so its output is dropped. */
    fun pending(paneId: Int): Boolean = paneId in seeding

    /** Whether a full capture of [paneId] is on its way, so the pane has no terminal to keep yet. */
    fun pendingFull(paneId: Int): Boolean = seeding[paneId]?.full == true

    /** Forgets the captures on their way, as after a reconnect; their replies change nothing. */
    fun clear() = seeding.clear()

    /**
     * Captures pane [paneId] and shows it. A width change captures again too, because
     * tmux rewraps the lines in its own way, which resizing the local terminal can't match.
     */
    fun seed(paneId: Int) {
        capture(paneId, TmuxControlCommands.capturePane(paneId), full = true) { target, state, replies ->
            target.view.showPane(paneId, state.size, TmuxPaneSeed.build(state, replies[1].lines, replies[2].lines, replies[3].lines))
        }
    }

    /**
     * Captures pane [paneId]'s screens after its height went from [old] to [new], and has
     * its terminal keep the history, unless the terminal can't follow tmux and the pane
     * needs a full capture.
     */
    fun resize(paneId: Int, old: TmuxPaneSize, new: TmuxPaneSize) {
        val commands = TmuxControlCommands.captureResizedPane(paneId, TmuxPaneResize.checkedRows(old.rows, new.rows))
        capture(paneId, commands, full = false) { target, state, replies ->
            target.view.resizePane(paneId, state, replies[1].lines, replies[2].lines, replies[3].lines) {
                onThread { if (paneId !in seeding && bindingOf(paneId) != null) seed(paneId) }
            }
        }
    }

    /**
     * Sends [commands], which read pane [paneId]'s state first, and passes their replies
     * to [show] unless a later capture of the pane started meanwhile.
     */
    private fun capture(
        paneId: Int,
        commands: List<String>,
        full: Boolean,
        show: (TmuxWindowBinding, TmuxPaneState, List<TmuxControlEvent.Reply>) -> Unit,
    ) {
        val capture = Capture(full)
        seeding[paneId] = capture
        link.sendAll(commands) { replies ->
            if (seeding[paneId] !== capture) return@sendAll
            seeding -= paneId
            val target = bindingOf(paneId) ?: return@sendAll
            val state = replies[0].takeUnless { it.failed }?.lines?.firstOrNull()?.let(TmuxPaneState::parse)
                ?: return@sendAll
            target.captured[paneId] = state.size
            show(target, state, replies)
        }
    }
}
