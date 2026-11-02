package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The shortest time between two snapshots of a terminal, one frame at 60 Hz. */
internal const val DEFAULT_SNAPSHOT_INTERVAL_MS = 16L

// One frame at 240 Hz, so a misreported rate can't make snapshots run without pause.
private const val MIN_SNAPSHOT_INTERVAL_MS = 4L
private const val MS_PER_SECOND = 1000f

/**
 * The snapshot interval for a display refreshing [refreshRateHz] times a second: one
 * frame, rounded down, and never longer than [DEFAULT_SNAPSHOT_INTERVAL_MS], so matching
 * the display can only make the terminal redraw sooner.
 */
internal fun snapshotIntervalMs(refreshRateHz: Float): Long {
    if (!(refreshRateHz > 0f)) return DEFAULT_SNAPSHOT_INTERVAL_MS
    return (MS_PER_SECOND / refreshRateHz).toLong().coerceIn(MIN_SNAPSHOT_INTERVAL_MS, DEFAULT_SNAPSHOT_INTERVAL_MS)
}

/**
 * The snapshot interval, [ms]: one frame of the default display while [enabled] holds,
 * following its refresh rate as it changes, and [DEFAULT_SNAPSHOT_INTERVAL_MS] otherwise.
 * It listens to the display only while enabled. Read [ms] from any thread.
 */
internal class DisplayFrameInterval(
    context: Context,
    enabled: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listening = false

    @Volatile var ms: Long = DEFAULT_SNAPSHOT_INTERVAL_MS
        private set

    private val listener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) readRefreshRate()
            }
        }

    init {
        scope.launch { enabled.collect { on -> if (on) startListening() else stopListening() } }
    }

    private fun startListening() {
        val manager = displayManager ?: return
        if (!listening) {
            manager.registerDisplayListener(listener, mainHandler)
            listening = true
        }
        readRefreshRate()
    }

    private fun stopListening() {
        if (listening) {
            displayManager?.unregisterDisplayListener(listener)
            listening = false
        }
        ms = DEFAULT_SNAPSHOT_INTERVAL_MS
    }

    private fun readRefreshRate() {
        val rate = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        ms = snapshotIntervalMs(rate)
    }
}
