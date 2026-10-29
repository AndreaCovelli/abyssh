package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays Kitty animations by taking a snapshot whenever the next frame is due. They play
 * only while the terminal is on screen, so a looping image in a background tab doesn't
 * keep rebuilding snapshots; off screen, an animation pauses on its current frame.
 *
 * [tick] advances the animations and returns the milliseconds until the next frame, or a
 * negative value when nothing plays. [requestSnapshot] takes a snapshot, which calls
 * [advance]. Every call must run on [dispatcher].
 */
internal class AnimationFrames(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val tick: () -> Long,
    private val requestSnapshot: () -> Unit,
) {
    private var onScreen = false
    private var nextFrame: Job? = null

    fun setOnScreen(visible: Boolean) {
        if (onScreen == visible) return
        onScreen = visible
        if (visible) requestSnapshot() else cancel()
    }

    /**
     * Advances the animations for the snapshot about to be taken, so it shows the frame
     * due now, and schedules the snapshot for the frame after.
     */
    fun advance() {
        cancel()
        if (!onScreen) return
        val delayMs = tick()
        if (delayMs < 0) return
        nextFrame = scope.launch(dispatcher) {
            delay(delayMs)
            nextFrame = null
            requestSnapshot()
        }
    }

    fun cancel() {
        nextFrame?.cancel()
        nextFrame = null
    }
}
