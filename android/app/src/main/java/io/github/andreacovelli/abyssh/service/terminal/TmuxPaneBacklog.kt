package io.github.andreacovelli.abyssh.service.terminal

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel

// Enough pane output to keep every pane busy, small enough that `cat` of a huge file
// can't run the app out of memory while the panes catch up.
internal const val TMUX_PANE_BACKLOG_BYTES = 4L * 1024 * 1024

/**
 * Pane output the control session has handed to its tabs' threads and they haven't
 * written yet. The control session stops reading while there is too much of it, so the
 * SSH window fills and tmux holds the output, or pauses the pane, instead of the app.
 */
internal class TmuxPaneBacklog(private val limitBytes: Long = TMUX_PANE_BACKLOG_BYTES) {
    private val queued = AtomicLong()
    private val drained = Channel<Unit>(Channel.CONFLATED)

    val bytes: Long get() = queued.get()

    /** [count] bytes were handed to a pane thread. Safe from any thread. */
    fun add(count: Int) {
        queued.addAndGet(count.toLong())
    }

    /** [count] bytes were written, or dropped. Safe from any thread. */
    fun remove(count: Int) {
        if (queued.addAndGet(-count.toLong()) <= limitBytes / 2) drained.trySend(Unit)
    }

    /** Waits until the backlog is back to half the limit, if it is over the limit. */
    suspend fun awaitRoom() {
        if (queued.get() <= limitBytes) return
        while (queued.get() > limitBytes / 2) drained.receive()
    }
}
