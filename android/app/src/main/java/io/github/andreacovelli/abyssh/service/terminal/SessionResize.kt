package io.github.andreacovelli.abyssh.service.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "TerminalSession"

// The view's size changes on every frame while the keyboard slides in or out, so the far
// end hears only the size that settles, as tmux control mode does.
private const val REMOTE_RESIZE_SETTLE_MS = 150L
private const val REMOTE_RESIZE_RETRY_MS = 500L

/**
 * Resizes a tab's terminal to its view, and gives the far end, through [sendRemote], the
 * size that settles. Every call must run on the session thread, [dispatcher].
 */
// Resizing crosses JNI and the network, which throw many exception types; each failure
// goes to [onFailed], which reconnects or reports it.
@Suppress("TooGenericExceptionCaught")
internal class SessionResize(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val emulator: TerminalEmulator,
    private val sendRemote: (TerminalGeometry) -> Boolean,
    private val flushPtyWrites: () -> Unit,
    private val onFailed: (Exception) -> Unit,
) {
    // The view's latest size, and the size the far end was given, or null while no
    // transport is open. They differ while a resize settles.
    private var viewGeometry: TerminalGeometry? = null
    private var remoteGeometry: TerminalGeometry? = null
    private var job: Job? = null

    // Whether the grid waits for the size that settles rather than following the view.
    private var gridHeld = false

    fun onViewResized(geometry: TerminalGeometry) {
        viewGeometry = geometry
        // The alternate screen keeps no scrollback, so a shrink loses the rows it cuts off,
        // and its program redraws only once the far end hears of a new size. While it is
        // up, the grid takes the size that settles, along with the far end: switching apps
        // can shrink the view for a moment under the last app's keyboard, and following
        // that would leave the screen shifted up, under a stale copy of a status line.
        gridHeld = remoteGeometry != null && emulator.isAlternateScreen()
        if (!gridHeld && !resizeGrid(geometry)) return
        job?.cancel()
        job = scope.launch(dispatcher) {
            delay(REMOTE_RESIZE_SETTLE_MS)
            sync()
        }
    }

    /** The far end opened at [geometry]. */
    fun opened(geometry: TerminalGeometry) {
        remoteGeometry = geometry
    }

    /** The far end closed or failed, so nothing more is sent until it opens again. */
    fun remoteClosed() {
        remoteGeometry = null
    }

    /** Stops a resize that is settling, as the tab closes. */
    fun cancel() {
        job?.cancel()
        job = null
        remoteGeometry = null
    }

    /**
     * Gives the far end the view's size, unless it already has it or nothing is open. The
     * view's, not the terminal's: a Mosh server can still be resizing the terminal to an
     * older size.
     */
    fun sync() {
        val sent = remoteGeometry ?: return
        val geometry = viewGeometry ?: return
        if (gridHeld) {
            gridHeld = false
            if (geometry != emulator.geometry && !resizeGrid(geometry)) return
        }
        if (geometry == sent) return
        try {
            if (sendRemote(geometry)) {
                remoteGeometry = geometry
            } else {
                // The send buffer is full; try again once it has had time to drain.
                job?.cancel()
                job = scope.launch(dispatcher) {
                    delay(REMOTE_RESIZE_RETRY_MS)
                    sync()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            onFailed(e)
        }
    }

    /** Gives the grid [geometry]; false when no terminal is open or the resize failed. */
    private fun resizeGrid(geometry: TerminalGeometry): Boolean {
        emulator.geometry = geometry
        if (!emulator.isOpen) return false
        return try {
            emulator.applyGeometry()
            // Flush pty output from the resize (e.g. the DEC 2048 in-band size report)
            // so Textual-based TUIs detect the resize.
            flushPtyWrites()
            emulator.setMouseEncodingSize()
            emulator.requestSnapshot(force = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            onFailed(e)
            false
        }
    }
}
