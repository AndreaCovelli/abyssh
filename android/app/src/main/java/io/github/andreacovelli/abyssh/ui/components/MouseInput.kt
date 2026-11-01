package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.dp

/** Runs [onClick] when a mouse's right button is pressed and released over the element. */
fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = this then MouseButtonClickElement(MouseButton.Secondary, onClick)

/** Runs [onClick] when a mouse's middle button is pressed and released over the element. */
fun Modifier.onMiddleClick(onClick: () -> Unit): Modifier = this then MouseButtonClickElement(MouseButton.Tertiary, onClick)

private enum class MouseButton(val isPressed: (PointerButtons) -> Boolean) {
    Secondary({ it.isSecondaryPressed }),
    Tertiary({ it.isTertiaryPressed }),
}

private data class MouseButtonClickElement(
    val button: MouseButton,
    val onClick: () -> Unit,
) : ModifierNodeElement<MouseButtonClickNode>() {
    override fun create() = MouseButtonClickNode(button, onClick)

    override fun update(node: MouseButtonClickNode) {
        node.button = button
        node.onClick = onClick
    }
}

/**
 * Watches the initial pass, ahead of a `clickable` placed after it in the chain, and
 * consumes the button's press so a right or middle click doesn't also count as a tap.
 * [onClick] updates in place, so a recomposition mid-click doesn't lose the press.
 */
private class MouseButtonClickNode(
    var button: MouseButton,
    var onClick: () -> Unit,
) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    var armed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = button.isPressed(event.buttons)
                        when {
                            event.type == PointerEventType.Press && pressed -> armed = true
                            event.type == PointerEventType.Release && armed && !pressed -> {
                                armed = false
                                val position = event.changes.first().position
                                val inside = position.x in 0f..size.width.toFloat() &&
                                    position.y in 0f..size.height.toFloat()
                                if (inside) onClick()
                            }
                            !armed -> continue
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        )
    }
}

/**
 * A mouse's vertical wheel scrolls a horizontal row, which on its own only follows a
 * horizontal wheel or a touchpad's sideways swipe.
 */
fun Modifier.verticalWheelScrollsHorizontally(state: ScrollState): Modifier = pointerInput(state) {
    val notchPx = WHEEL_NOTCH.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull()?.takeIf { event.type == PointerEventType.Scroll }
            val delta = change?.scrollDelta
            if (delta != null && delta.x == 0f && delta.y != 0f) {
                state.dispatchRawDelta(delta.y * notchPx)
                change.consume()
            }
        }
    }
}

/** Runs [onHover] each time a mouse or stylus starts hovering over [interactionSource]'s element. */
@Composable
fun HoverEffect(interactionSource: InteractionSource, onHover: () -> Unit) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val currentOnHover by rememberUpdatedState(onHover)
    LaunchedEffect(hovered) { if (hovered) currentOnHover() }
}

/**
 * Scrolls just enough to show the item at [index] whole, so a list whose highlight follows
 * the hovered row doesn't move under a still pointer and hand the highlight on.
 */
suspend fun LazyListState.revealItem(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> animateScrollToItem(index)
        item.offset < info.viewportStartOffset -> animateScrollBy((item.offset - info.viewportStartOffset).toFloat())
        item.offset + item.size > info.viewportEndOffset ->
            animateScrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
    }
}

private val WHEEL_NOTCH = 48.dp
