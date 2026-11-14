package io.github.andreacovelli.abyssh.service.terminal

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

// The view's size changes on every frame while the keyboard slides in or out, and a frame
// or two after the screen around the terminal changes. It has settled once it has kept
// one size this long.
internal const val VIEW_SIZE_SETTLE_MS = 150L

// The longest a connection waits for the view to settle, as when the keyboard never shows.
internal const val MAX_VIEW_SIZE_WAIT_MS = 1_000L

/**
 * The size of the terminal view on screen, which every tab shares. A connection opens at
 * the size the view settles on, so the far end doesn't draw a screen that is resized as
 * soon as it shows. Safe to call from any thread.
 */
internal class TerminalViewSize(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class View(
        val geometry: TerminalGeometry? = null,
        val changedAtMs: Long = 0L,
        val keyboardPending: Boolean = false,
    ) {
        /** Whether the size can't be trusted however long it is kept. */
        val awaitsLayout: Boolean get() = geometry == null || keyboardPending
    }

    private val view = MutableStateFlow(View())

    /** The view's size, or null before it was first laid out. */
    val current: TerminalGeometry? get() = view.value.geometry

    /** The view was laid out at [geometry]. */
    fun set(geometry: TerminalGeometry) {
        view.update { if (it.geometry == geometry) it else it.copy(geometry = geometry, changedAtMs = clock()) }
    }

    /** Whether the keyboard was asked for and isn't up yet, so the view is about to shrink. */
    fun setKeyboardPending(pending: Boolean) {
        view.update { if (it.keyboardPending == pending) it else it.copy(keyboardPending = pending, changedAtMs = clock()) }
    }

    /**
     * The screen around the terminal is about to change, as when a tab starts to connect
     * and its status replaces what was there, so the size has to settle again.
     */
    fun unsettle() {
        view.update { it.copy(changedAtMs = clock()) }
    }

    /**
     * Waits until the view has kept one size for [VIEW_SIZE_SETTLE_MS], with the keyboard
     * up when it was asked for, and returns that size. After [MAX_VIEW_SIZE_WAIT_MS] it
     * returns the size the view has, or null when it was never laid out.
     */
    suspend fun awaitSettled(): TerminalGeometry? {
        withTimeoutOrNull(MAX_VIEW_SIZE_WAIT_MS) {
            var seen = view.value
            while (true) {
                val last = seen
                val settlesInMs = last.changedAtMs + VIEW_SIZE_SETTLE_MS - clock()
                seen = when {
                    last.awaitsLayout -> view.first { it != last }
                    settlesInMs > 0 -> withTimeoutOrNull(settlesInMs) { view.first { it != last } } ?: last
                    else -> break
                }
            }
        }
        return current
    }
}
