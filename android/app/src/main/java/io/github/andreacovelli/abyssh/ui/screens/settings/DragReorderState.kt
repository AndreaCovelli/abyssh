package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Tracks a long-press drag that reorders a list one slot per [stepPx] of travel. The
 * dragged item follows the pointer by [offsetPx]; each time the drag covers a full step,
 * [drag] asks the list to move the item and pulls the offset back by one step.
 */
@Stable
internal class DragReorderState<K : Any>(private val stepPx: Float) {
    var draggingKey by mutableStateOf<K?>(null)
        private set
    var offsetPx by mutableFloatStateOf(0f)
        private set
    private var remainderPx = 0f

    fun offsetFor(key: K): Float = if (draggingKey == key) offsetPx else 0f

    fun start(key: K) {
        draggingKey = key
        remainderPx = 0f
        offsetPx = 0f
    }

    fun stop() {
        draggingKey = null
        remainderPx = 0f
        offsetPx = 0f
    }

    /**
     * Adds [delta] pixels of travel. [move] receives `1` or `-1` and returns whether the
     * item moved; a `false` (the item reached an end of the list) stops further steps.
     */
    fun drag(delta: Float, move: (direction: Int) -> Boolean) {
        if (draggingKey == null) return
        remainderPx += delta
        offsetPx += delta
        while (abs(remainderPx) >= stepPx) {
            val direction = if (remainderPx > 0) 1 else -1
            if (!move(direction)) break
            remainderPx -= direction * stepPx
            offsetPx -= direction * stepPx
        }
    }
}

/** Returns a copy with the element at [from] moved to [to], or `null` when nothing moves. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T>? {
    if (from !in indices || to !in indices || from == to) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}
