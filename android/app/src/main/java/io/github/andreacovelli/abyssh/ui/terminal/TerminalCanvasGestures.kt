package io.github.andreacovelli.abyssh.ui.terminal

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseAction
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseButton
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.abs
import kotlin.math.round

/** Rows scrolled per mouse wheel notch, as in most desktop terminals. */
private const val WHEEL_ROWS_PER_NOTCH = 3f

/** How often a selection drag held at the canvas edge scrolls, and by at most how many rows. */
private const val AUTO_SCROLL_INTERVAL_MS = 55L
private const val AUTO_SCROLL_MAX_ROWS = 8

private const val LONG_PRESS_SLOP_FACTOR = 1.5f

/** A drag counts as vertical when it moves this much more vertically than sideways. */
private const val VERTICAL_INTENT_RATIO = 1.2f

/** A two-finger tap must lift within this many long-press timeouts. */
private const val TWO_FINGER_TAP_TIMEOUTS = 2

internal data class TerminalScrollDelta(val delta: Int, val x: Float, val y: Float)

/** What the canvas calls back for pointer input; see [TerminalCanvas] for each. */
internal class TerminalCanvasCallbacks(
    val onSelectionChange: (TerminalSelection?) -> Unit,
    val onTap: () -> Unit,
    val onPrimaryClick: (mods: Int, x: Float, y: Float) -> Unit,
    val onLinkTap: (col: Int, row: Int, textUrl: String?, noLink: () -> Unit) -> Unit,
    val onSecondaryClick: (mods: Int, x: Float, y: Float) -> Unit,
    val onMouseButton: (button: Int, action: Int, mods: Int, x: Float, y: Float) -> Unit,
    val onAppSelectionDrag: (action: Int, mods: Int, x: Float, y: Float) -> Unit,
    val onShiftSelectionStart: () -> Unit,
    val onFontSizeChange: (sizeSp: Float) -> Unit,
)

/**
 * The canvas as of its last composition, which its gestures read and call. The snapshot
 * is read when a gesture asks for it, since snapshots change without a recomposition.
 */
internal class TerminalGestureEnv(
    private val snapshotSource: () -> TerminalSnapshot,
    private val fitSnapshotToWidth: Boolean,
    val selection: TerminalSelection?,
    val cell: TerminalCellMetrics,
    val canvasSize: IntSize,
    val fontSizeSp: Float,
    val fontSizeRange: ClosedFloatingPointRange<Float>,
    val stickyModifiers: ModifierState,
    val haptics: HapticFeedback,
    val callbacks: TerminalCanvasCallbacks,
) {
    val snapshot: TerminalSnapshot get() = snapshotSource()

    private val placement: SnapshotPlacement
        get() = SnapshotPlacement.of(
            fitSnapshotToWidth,
            snapshot,
            cell,
            canvasSize.width.toFloat(),
            canvasSize.height.toFloat(),
        )

    fun toSnapshot(position: Offset): Offset = placement.toSnapshot(position)

    /** The cell under a point in snapshot pixels. */
    fun cellAt(position: Offset): Int? = snapshot.cellAt(position.x, position.y, cell.width, cell.height)

    fun appDrag(action: Int, mods: Int, position: Offset) =
        callbacks.onAppSelectionDrag(action, mods, position.x, position.y)

    /** Ghostty modifier bits for a mouse event with the [keyboard] keys held; see [mouseModifierBits]. */
    fun mouseMods(keyboard: PointerKeyboardModifiers, includeShift: Boolean = true): Int =
        mouseModifierBits(stickyModifiers, keyboard.toModifierState(), includeShift)

    fun longPressHaptic() = haptics.performHapticFeedback(HapticFeedbackType.LongPress)
}

/** Touch timing and distances, from the platform's view configuration. */
internal data class TerminalGestureConfig(
    val touchSlopPx: Float,
    val longPressSlopPx: Float,
    val longPressTimeoutMs: Long,
    val doubleTapTimeoutMs: Long,
    val doubleTapSlopPx: Float,
    val autoScrollEdgePx: Float,
)

@Composable
internal fun rememberTerminalGestureConfig(): TerminalGestureConfig {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val viewConfiguration = ViewConfiguration.get(context)
        val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
        TerminalGestureConfig(
            touchSlopPx = touchSlop,
            longPressSlopPx = touchSlop * LONG_PRESS_SLOP_FACTOR,
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
            doubleTapSlopPx = viewConfiguration.scaledDoubleTapSlop.toFloat(),
            autoScrollEdgePx = with(density) { 48.dp.toPx() },
        )
    }
}

/** Tracks double-tap timing and position without triggering recomposition. */
internal class DoubleTapState {
    // Far in the past, so the first tap is never taken for the second of a double tap.
    private var lastTime: Long = Long.MIN_VALUE / 2
    private var lastPosition: Offset = Offset.Zero

    /** Records a tap, and returns whether it completes a double tap. */
    fun register(position: Offset, time: Long, timeoutMs: Long, slopPx: Float): Boolean {
        val isDouble = time - lastTime < timeoutMs && (position - lastPosition).getDistance() < slopPx
        lastTime = time
        lastPosition = position
        return isDouble
    }
}

private enum class DragMode {
    None,
    ClientSelectionDrag,
    AppMouseDrag,
}

/**
 * Turns pointer input on the terminal canvas into selections, clicks, scrolling, and
 * font size changes. Touch: tap, double-tap for a word, long-press to select or to
 * hand the drag to the app, one-finger scroll, pinch to zoom, two-finger tap for a
 * right click. Mouse: the wheel scrolls, the left button selects or clicks, and the
 * right and middle buttons go to the app.
 */
internal class TerminalCanvasGestures(
    private val env: State<TerminalGestureEnv>,
    private val config: TerminalGestureConfig,
    private val scroll: (TerminalScrollDelta) -> Unit,
) {
    /**
     * True while a selection drag held at the canvas edge scrolls the viewport. The
     * selection's anchor then follows the scroll while its end stays under the finger.
     */
    var autoScrollingSelection = false
        private set

    private val doubleTap = DoubleTapState()

    val modifier: Modifier = Modifier
        .pointerInput(this) { awaitPointerEventScope { wheelScroll() } }
        .pointerInput(this) { awaitEachGesture { gesture() } }

    // Mouse wheel and touchpad scrolling. These don't press a pointer, so the gesture
    // handler never sees them.
    private suspend fun AwaitPointerEventScope.wheelScroll() {
        var remainder = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull()
            if (event.type == PointerEventType.Scroll && change != null) {
                remainder += change.scrollDelta.y * WHEEL_ROWS_PER_NOTCH
                val rows = remainder.toInt()
                if (rows != 0) {
                    remainder -= rows
                    val p = env.value.toSnapshot(change.position)
                    scroll(TerminalScrollDelta(rows, p.x, p.y))
                }
                change.consume()
            }
        }
    }

    private suspend fun AwaitPointerEventScope.gesture() {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = HeldModifiers.of(env.value, currentEvent.keyboardModifiers)
        val isMouse = down.type == PointerType.Mouse
        val mouseButton = if (isMouse) nonPrimaryMouseButton(currentEvent.buttons) else null
        when {
            mouseButton != null -> forwardMouseButton(down, mouseButton)
            isMouse -> mouseLeftButton(down, held)
            else -> touch(down, held)
        }
    }

    /**
     * A physical right or middle click, passed through as is with motion while held. The
     * modifiers held go with it, so tmux sees Alt+right click as M-MouseDown3Pane.
     */
    private suspend fun AwaitPointerEventScope.forwardMouseButton(down: PointerInputChange, button: Int) {
        fun send(action: Int, position: Offset, event: PointerEvent) {
            val p = env.value.toSnapshot(position)
            env.value.callbacks.onMouseButton(button, action, env.value.mouseMods(event.keyboardModifiers), p.x, p.y)
        }
        send(TerminalMouseAction.Press, down.position, currentEvent)
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed || nonPrimaryMouseButton(event.buttons) != button) {
                send(TerminalMouseAction.Release, change.position, event)
                return
            }
            if (change.position != change.previousPosition) send(TerminalMouseAction.Motion, change.position, event)
            change.consume()
        }
    }

    /**
     * Left button of a physical mouse, from press to release. When the remote app takes
     * mouse drags the button goes to it as is; otherwise dragging selects text and a
     * click without moving acts like a tap.
     */
    private suspend fun AwaitPointerEventScope.mouseLeftButton(down: PointerInputChange, held: HeldModifiers) {
        val start = env.value
        val toApp = start.snapshot.appHandlesSelectionDrag && !held.shift
        if (toApp) start.appDrag(TerminalMouseAction.Press, held.primary, start.toSnapshot(down.position))
        down.consume()
        val downCell = start.cellAt(start.toSnapshot(down.position))
        var selecting = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            val current = env.value
            val pos = current.toSnapshot(change.position)
            if (!change.pressed) {
                when {
                    toApp -> {
                        current.appDrag(TerminalMouseAction.Release, held.primary, pos)
                        current.callbacks.onTap()
                    }
                    !selecting -> tap(down.position, change.uptimeMillis, held)
                }
                return
            }
            if (change.position != change.previousPosition) {
                if (toApp) {
                    current.appDrag(TerminalMouseAction.Motion, held.primary, pos)
                } else {
                    val cell = current.cellAt(pos)
                    if (!selecting && cell != null && downCell != null && cell != downCell) {
                        selecting = true
                        current.callbacks.onSelectionChange(TerminalSelection(downCell, downCell))
                    }
                    if (selecting && cell != null) {
                        updateSelectionDrag(current.selection, cell, current.callbacks.onSelectionChange)
                    }
                }
            }
            change.consume()
        }
    }

    private suspend fun AwaitPointerEventScope.touch(down: PointerInputChange, held: HeldModifiers) {
        val gesture = TouchGesture(down, held)
        try {
            while (!gesture.finished) {
                val timeout = gesture.timeoutMs()
                val event = if (timeout == null) awaitPointerEvent() else withTimeoutOrNull(timeout) { awaitPointerEvent() }
                if (event == null) gesture.onTimeout() else gesture.onEvent(event)
            }
        } finally {
            gesture.end()
        }
    }

    /** A tap or click that didn't move: double-tap selects a word, else clear the selection or click. */
    private fun tap(position: Offset, time: Long, held: HeldModifiers) {
        val current = env.value
        val tapPos = current.toSnapshot(position)
        when {
            doubleTap.register(tapPos, time, config.doubleTapTimeoutMs, config.doubleTapSlopPx) ->
                selectWordAt(current, tapPos)
            current.selection != null -> current.callbacks.onSelectionChange(null)
            else -> click(current, tapPos, held)
        }
    }

    private fun selectWordAt(current: TerminalGestureEnv, position: Offset) {
        val cell = current.cellAt(position) ?: return
        val word = current.snapshot.wordAt(cell) ?: return
        current.callbacks.onSelectionChange(TerminalSelection(word.first, word.last))
        current.longPressHaptic()
    }

    private fun click(current: TerminalGestureEnv, position: Offset, held: HeldModifiers) {
        val snapshot = current.snapshot
        val callbacks = current.callbacks
        val click = {
            callbacks.onPrimaryClick(held.primary, position.x, position.y)
            callbacks.onTap()
        }
        val cell = current.cellAt(position)
        if (cell == null || (snapshot.appTracksMouse && !held.shift)) {
            click()
            return
        }
        if (snapshot.appTracksMouse) callbacks.onShiftSelectionStart()
        callbacks.onLinkTap(cell % snapshot.cols, cell / snapshot.cols, snapshot.urlAt(cell)) {
            // Shift+tap is for links only; the app doesn't see it.
            if (snapshot.appTracksMouse) callbacks.onTap() else click()
        }
    }

    /** One touch gesture, from the first finger down to the last finger up. */
    private inner class TouchGesture(private val down: PointerInputChange, private val held: HeldModifiers) {
        var finished = false
            private set
        private var dragMode = DragMode.None
        private var dragRemainder = 0f
        private var startPinchDistance: Float? = null
        private var anchorFontSp = 0f
        private var lastSentSp = 0f
        private var didScroll = false
        private var didPinch = false

        // A two-finger tap is a pinch where neither finger moved.
        private val fingerStarts = mutableMapOf(down.id to down.position)
        private var fingersMoved = false
        private var didDragGesture = false
        private var selectionCleared = false
        private var lastSinglePointerId = down.id
        private var lastEventUptime = down.uptimeMillis
        private val longPressDeadline = down.uptimeMillis + config.longPressTimeoutMs
        private var lastPointerPos = down.position
        private var autoScrollDir = 0

        /** How long to wait for the next event before [onTimeout], or null to wait for it. */
        fun timeoutMs(): Long? = when {
            dragMode == DragMode.None && !didScroll && !didPinch && !didDragGesture ->
                (longPressDeadline - lastEventUptime).coerceAtLeast(1L)
            dragMode == DragMode.ClientSelectionDrag && autoScrollDir != 0 -> AUTO_SCROLL_INTERVAL_MS
            else -> null
        }

        fun onTimeout() {
            if (dragMode == DragMode.None) startLongPress() else autoScrollStep()
        }

        fun onEvent(event: PointerEvent) {
            lastEventUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventUptime
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                onRelease()
                return
            }
            pressed.forEach { change ->
                val start = fingerStarts.getOrPut(change.id) { change.position }
                if ((change.position - start).getDistance() > config.touchSlopPx) fingersMoved = true
            }
            if (pressed.size >= 2) onPinch(pressed) else onSinglePointer(pressed)
        }

        /** Runs when the gesture ends, however it ends. */
        fun end() {
            autoScrollingSelection = false
            if (dragMode == DragMode.AppMouseDrag) {
                val current = env.value
                current.appDrag(TerminalMouseAction.Release, held.primary, current.toSnapshot(lastPointerPos))
            }
        }

        private fun startLongPress() {
            val current = env.value
            val snapshot = current.snapshot
            val downPos = current.toSnapshot(down.position)
            if (snapshot.appHandlesSelectionDrag && !held.shift) {
                current.appDrag(TerminalMouseAction.Press, held.primary, downPos)
                current.longPressHaptic()
                didDragGesture = true
                dragMode = DragMode.AppMouseDrag
                return
            }
            val cell = current.cellAt(downPos) ?: return
            current.callbacks.onSelectionChange(TerminalSelection(cell, cell))
            current.longPressHaptic()
            if (snapshot.appHandlesSelectionDrag) current.callbacks.onShiftSelectionStart()
            dragMode = DragMode.ClientSelectionDrag
        }

        private fun autoScrollStep() {
            val current = env.value
            val pos = lastPointerPos
            val edge = config.autoScrollEdgePx
            val depth = if (autoScrollDir > 0) {
                pos.y - (current.canvasSize.height - edge)
            } else {
                edge - pos.y
            }.coerceAtLeast(0f)
            val rows = (depth / current.cell.height).toInt().coerceIn(1, AUTO_SCROLL_MAX_ROWS)
            scroll(TerminalScrollDelta(autoScrollDir * rows, pos.x, pos.y))
        }

        private fun onRelease() {
            finished = true
            autoScrollDir = 0
            autoScrollingSelection = false
            val releasedDragMode = dragMode
            dragMode = DragMode.None
            val current = env.value
            if (releasedDragMode == DragMode.AppMouseDrag) {
                current.appDrag(TerminalMouseAction.Release, held.primary, current.toSnapshot(lastPointerPos))
            }
            if (releasedDragMode != DragMode.None) return
            val heldMs = lastEventUptime - down.uptimeMillis
            val twoFingerTap = didPinch &&
                !fingersMoved &&
                !didScroll &&
                heldMs < config.longPressTimeoutMs * TWO_FINGER_TAP_TIMEOUTS
            when {
                twoFingerTap -> {
                    val tapPos = current.toSnapshot(down.position)
                    current.callbacks.onSecondaryClick(held.secondary, tapPos.x, tapPos.y)
                }
                !didScroll && !didPinch && !didDragGesture -> tap(down.position, lastEventUptime, held)
            }
        }

        private fun onPinch(pressed: List<PointerInputChange>) {
            didPinch = true
            val current = env.value
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (startPinchDistance == null && distance > 0f) {
                startPinchDistance = distance
                anchorFontSp = current.fontSizeSp
                lastSentSp = round(anchorFontSp)
            }
            val startDistance = startPinchDistance
            if (startDistance != null && distance > 0f) {
                val steppedSp = round(anchorFontSp * (distance / startDistance)).coerceIn(current.fontSizeRange)
                if (steppedSp != lastSentSp) {
                    current.callbacks.onFontSizeChange(steppedSp)
                    current.haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastSentSp = steppedSp
                }
            }
            pressed.forEach { change -> if (change.position != change.previousPosition) change.consume() }
        }

        private fun onSinglePointer(pressed: List<PointerInputChange>) {
            startPinchDistance = null
            val change = pressed.firstOrNull { it.id == lastSinglePointerId }
                ?: pressed.first().also { lastSinglePointerId = it.id }
            val current = env.value
            val pos = current.toSnapshot(change.position)
            val cell = current.cellAt(pos)
            when {
                dragMode == DragMode.AppMouseDrag -> appDragMove(current, change, pos)
                dragMode == DragMode.ClientSelectionDrag && cell != null -> selectionDragMove(current, change, cell)
                else -> scrollMove(current, change, pos)
            }
        }

        private fun appDragMove(current: TerminalGestureEnv, change: PointerInputChange, pos: Offset) {
            lastPointerPos = change.position
            stopAutoScroll()
            if (change.position != change.previousPosition) {
                current.appDrag(TerminalMouseAction.Motion, held.primary, pos)
                change.consume()
            }
        }

        private fun selectionDragMove(current: TerminalGestureEnv, change: PointerInputChange, cell: Int) {
            lastPointerPos = change.position
            updateSelectionDrag(current.selection, cell, current.callbacks.onSelectionChange)
            val edge = config.autoScrollEdgePx
            autoScrollDir = when {
                change.position.y < edge -> -1
                change.position.y > current.canvasSize.height - edge -> 1
                else -> 0
            }
            autoScrollingSelection = autoScrollDir != 0
            if (change.position != change.previousPosition) change.consume()
        }

        private fun scrollMove(current: TerminalGestureEnv, change: PointerInputChange, pos: Offset) {
            val previous = current.toSnapshot(change.previousPosition)
            val movedDistance = (pos - current.toSnapshot(down.position)).getDistance()
            val abortSlopPx = if (dragMode != DragMode.None) config.touchSlopPx else config.longPressSlopPx
            if (movedDistance > abortSlopPx) {
                didDragGesture = true
                if (current.selection != null && !selectionCleared) {
                    current.callbacks.onSelectionChange(null)
                    selectionCleared = true
                }
            }
            stopAutoScroll()
            val dragX = pos.x - previous.x
            val dragY = pos.y - previous.y
            if (abs(dragY) > abs(dragX) * VERTICAL_INTENT_RATIO) dragRemainder += dragY / current.cell.height
            if (didDragGesture && abs(dragRemainder) >= 1f) {
                val delta = dragRemainder.toInt()
                dragRemainder -= delta
                didScroll = true
                scroll(TerminalScrollDelta(-delta, pos.x, pos.y))
            }
            if (change.position != change.previousPosition) change.consume()
        }

        private fun stopAutoScroll() {
            autoScrollDir = 0
            autoScrollingSelection = false
        }
    }
}

private fun updateSelectionDrag(
    existing: TerminalSelection?,
    selectedCell: Int,
    onSelectionChange: (TerminalSelection?) -> Unit,
) {
    if (existing == null || existing.focusIndex != selectedCell) {
        onSelectionChange((existing ?: TerminalSelection(selectedCell, selectedCell)).copy(focusIndex = selectedCell))
    }
}

/** Ghostty button for a right or middle mouse press, or null for the left button. */
private fun nonPrimaryMouseButton(buttons: PointerButtons): Int? = when {
    buttons.isPrimaryPressed -> null
    buttons.isSecondaryPressed -> TerminalMouseButton.Right
    buttons.isTertiaryPressed -> TerminalMouseButton.Middle
    else -> null
}

/**
 * The modifiers held when a gesture starts. Shift keeps a left click or drag in the
 * terminal, for selection and links, so it is left out of the bits the app gets for left
 * button events ([primary]) but kept for a right click ([secondary]).
 */
private class HeldModifiers(val shift: Boolean, val primary: Int, val secondary: Int) {
    companion object {
        fun of(env: TerminalGestureEnv, keyboard: PointerKeyboardModifiers) = HeldModifiers(
            shift = env.stickyModifiers.shift || keyboard.isShiftPressed,
            primary = env.mouseMods(keyboard, includeShift = false),
            secondary = env.mouseMods(keyboard),
        )
    }
}

/**
 * Ghostty modifier bits for a mouse event: the accessory bar's [sticky] modifiers and the
 * [keyboard] keys held, with Shift only when [includeShift].
 */
internal fun mouseModifierBits(sticky: ModifierState, keyboard: ModifierState, includeShift: Boolean): Int = ModifierState(
    ctrl = sticky.ctrl || keyboard.ctrl,
    alt = sticky.alt || keyboard.alt,
    shift = includeShift && (sticky.shift || keyboard.shift),
    cmd = sticky.cmd || keyboard.cmd,
).terminalMods()

private fun PointerKeyboardModifiers.toModifierState() = ModifierState(
    ctrl = isCtrlPressed,
    alt = isAltPressed,
    shift = isShiftPressed,
    cmd = isMetaPressed,
)
