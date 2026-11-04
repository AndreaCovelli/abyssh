package io.github.andreacovelli.abyssh.ui.terminal

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Each half of the blink cycle, as in Ghostty. */
private const val BLINK_INTERVAL_MS = 600L

/** Which half of the blink cycle the canvas draws. */
@Stable
internal class TerminalBlinkPhase {
    /** True in the off half: blinking text and a blinking cursor are hidden. */
    var off by mutableStateOf(false)
        internal set
}

/**
 * What the blink cycle depends on: it runs while anything blinks, and restarts on the
 * visible half when the cursor moves, so the cursor shows while typing.
 */
private data class BlinkKey(val cursorX: Int, val cursorY: Int, val cursorBlinks: Boolean, val textBlinks: Boolean) {
    val blinks: Boolean get() = cursorBlinks || textBlinks
}

/**
 * The blink phase for the snapshot: SGR 5 text always blinks, and the cursor blinks
 * when the app asks or, for an app that doesn't, when [cursorBlinkDefault] is on.
 * Nothing blinks with Android's "Remove animations" on, or while the screen is stopped.
 */
@Composable
internal fun rememberTerminalBlinkPhase(snapshot: () -> TerminalSnapshot, cursorBlinkDefault: Boolean): TerminalBlinkPhase {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase = remember { TerminalBlinkPhase() }
    val currentSnapshot by rememberUpdatedState(snapshot)
    LaunchedEffect(lifecycle, cursorBlinkDefault) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Read on each start, so a change made in system settings applies on return.
            if (!context.animationsEnabled()) {
                phase.off = false
                return@repeatOnLifecycle
            }
            snapshotFlow { currentSnapshot().blinkKey(cursorBlinkDefault) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    phase.off = false
                    while (key.blinks) {
                        delay(BLINK_INTERVAL_MS)
                        // Waits for a frame, so the cycle pauses while nothing draws.
                        withFrameNanos { }
                        phase.off = !phase.off
                    }
                }
        }
    }
    return phase
}

private fun TerminalSnapshot.blinkKey(cursorBlinkDefault: Boolean) = BlinkKey(
    cursorX = cursorX,
    cursorY = cursorY,
    cursorBlinks = cursorVisible && cursorBlinks(cursorBlinkDefault),
    textBlinks = hasBlinkText,
)

/** False when Android's "Remove animations" (animator duration scale 0) is on. */
private fun Context.animationsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
