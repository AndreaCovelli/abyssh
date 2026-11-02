package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How long a tab stays hidden before its connection stops the traffic that only keeps
// it fresh. Switching tabs briefly, or a glance at another app, keeps it live.
internal const val QUIET_AFTER_HIDDEN_MS = 30_000L

/**
 * Counts what shows a tab and calls [setQuiet] with true once nothing has shown it for
 * [delayMs], and with false as soon as something shows it again. A tab starts hidden.
 * Calls must run on the session thread, [dispatcher], where [setQuiet] runs too.
 */
internal class QuietWhenHidden(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val setQuiet: (Boolean) -> Unit,
    private val delayMs: Long = QUIET_AFTER_HIDDEN_MS,
) {
    private var viewers = 0
    private var quiet = false
    private var pending: Job? = scheduleQuiet()

    fun addViewer() {
        viewers++
        if (viewers > 1) return
        pending?.cancel()
        pending = null
        if (quiet) {
            quiet = false
            setQuiet(false)
        }
    }

    fun removeViewer() {
        if (viewers == 0) return
        viewers--
        if (viewers == 0) {
            pending?.cancel()
            pending = scheduleQuiet()
        }
    }

    /** Stops the countdown, as when the session is disposed. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun scheduleQuiet(): Job = scope.launch(dispatcher) {
        delay(delayMs)
        quiet = true
        setQuiet(true)
    }
}
