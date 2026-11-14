package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneRect
import kotlin.math.floor

// The most motion reports a repeat sends for a pointer far past a pane's edge.
private const val MAX_EDGE_REPEATS = 8

/**
 * Where to report a mouse event, in pixels from the tab's top left, and how many times to
 * report it again each repeat while the button stays down there: none unless the drag is
 * on its pane's top or bottom row.
 */
internal data class MousePoint(val x: Float, val y: Float, val edgeRepeats: Int = 0)

/**
 * Keeps a left-button drag on a plain tmux client inside the pane it started in. tmux
 * takes a drag that reaches a pane border for a drag on the border, and resizes the pane,
 * even when the drag started in a program that reads the mouse, such as nvim. And it
 * scrolls a copy-mode selection only while the pointer is on the pane's own top or
 * bottom row, which a pointer past the pane, on the status line or off the terminal,
 * never is. Kept inside the pane, the pointer stops on that row instead.
 *
 * tmux's own scrolling stops at the next report that moves the pointer along the row, so
 * a drag on that row is reported again while it stays there ([MousePoint.edgeRepeats]).
 * Each report scrolls a row, and more go out the further past the pane the pointer is.
 *
 * [listPanes] asks tmux where the panes are, once a drag, when the drag first moves, so
 * a click costs nothing. A drag that starts on a border, to resize, or whose panes can't
 * be listed, passes as it is. Calls run on the session thread.
 */
internal class TmuxDragConfinement(private val listPanes: () -> List<TmuxPaneRect>?) {
    private var press: MousePoint? = null
    private var listed = false
    private var pane: TmuxPaneRect? = null

    /** Where to report a mouse [action] of [button] at ([x], [y]), with cells of [cellWidth] by [cellHeight] pixels. */
    fun confine(button: Int, action: Int, x: Float, y: Float, cellWidth: Int, cellHeight: Int): MousePoint {
        val point = MousePoint(x, y)
        if (button != TerminalMouseButton.Left || cellWidth <= 0 || cellHeight <= 0) return point
        when (action) {
            TerminalMouseAction.Press -> {
                press = point
                listed = false
                pane = null
                return point
            }

            TerminalMouseAction.Motion -> {
                val start = press ?: return point
                if (!listed) {
                    listed = true
                    val col = floor(start.x / cellWidth).toInt()
                    val row = floor(start.y / cellHeight).toInt()
                    pane = listPanes()?.firstOrNull { it.contains(col, row) }
                }
            }
        }
        val inPane = pane
        if (action == TerminalMouseAction.Release) {
            press = null
            listed = false
            pane = null
        }
        if (inPane == null) return point
        val confined = keepIn(inPane, point, cellWidth, cellHeight)
        if (action != TerminalMouseAction.Motion) return confined
        return confined.copy(edgeRepeats = edgeRepeats(inPane, y, cellHeight))
    }

    private fun keepIn(pane: TmuxPaneRect, point: MousePoint, cellWidth: Int, cellHeight: Int): MousePoint {
        val left = (pane.x * cellWidth).toFloat()
        val top = (pane.y * cellHeight).toFloat()
        return MousePoint(
            point.x.coerceIn(left, left + pane.cols * cellWidth - 1f),
            point.y.coerceIn(top, top + pane.rows * cellHeight - 1f),
        )
    }

    /** One on the pane's top or bottom row, one more for each row past it; none between them. */
    private fun edgeRepeats(pane: TmuxPaneRect, y: Float, cellHeight: Int): Int {
        val firstRowBottom = ((pane.y + 1) * cellHeight).toFloat()
        val lastRowTop = ((pane.y + pane.rows - 1) * cellHeight).toFloat()
        val rowsPast = when {
            y < firstRowBottom -> (firstRowBottom - y) / cellHeight
            y >= lastRowTop -> (y - lastRowTop) / cellHeight
            else -> return 0
        }
        return rowsPast.toInt().coerceIn(1, MAX_EDGE_REPEATS)
    }
}
