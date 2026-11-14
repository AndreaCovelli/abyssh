package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxClientPane
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneRect
import kotlin.math.floor

// The most rows a turn of an edge scroll moves, for a pointer far past the pane.
private const val MAX_EDGE_SCROLL_ROWS = 8

/** Where to report a mouse event, in pixels from the tab's top left. */
internal data class MousePoint(val x: Float, val y: Float)

/**
 * The motions that make tmux scroll a selection a row: one at [inner], on the row next to
 * the pane's edge row, then one back where the drag is. [rows] is how many rows to scroll
 * each turn.
 */
internal data class EdgeScroll(val inner: MousePoint, val rows: Int)

/**
 * Where to report a mouse event ([at]), and the [scroll] to keep up for as long as the
 * button stays down there, when tmux should be scrolling the selection.
 */
internal data class ConfinedMouse(val at: MousePoint, val scroll: EdgeScroll? = null)

/**
 * Keeps a left-button drag on a plain tmux client inside the pane it started in. tmux
 * takes a drag that reaches a pane border for a drag on the border, and resizes the pane,
 * even when the drag started in a program that reads the mouse, such as nvim. And it
 * scrolls a copy-mode selection only while the pointer is on the pane's own top or
 * bottom row, which a pointer past the pane, on the status line or off the terminal,
 * never is. Kept inside the pane, the pointer stops on that row instead.
 *
 * tmux scrolls a row when a report moves the drag onto that row from another one, and
 * drops a report at the place of the last one. Its timer for going on from there can't
 * be relied on: a report that moves along the row stops it, and with `mode-keys vi` tmux
 * 3.8 never keeps it running. So a drag that rests where tmux should scroll comes with
 * the motions that scroll a row ([ConfinedMouse.scroll]), for the caller to repeat: one
 * row a turn on the pane's edge row, more the further past the pane the pointer is. A
 * drag along the row it started on, which selects within that line, doesn't scroll
 * until it goes past the pane or comes back to the row. Nor does a drag in a pane whose
 * program takes it, which is reported only where it is.
 *
 * [listPanes] asks tmux where the panes are, once a drag, when the drag first moves, so
 * a click costs nothing. A drag that starts on a border, to resize, or whose panes can't
 * be listed, passes as it is. Calls run on the session thread.
 */
internal class TmuxDragConfinement(private val listPanes: () -> List<TmuxClientPane>?) {
    private var press: MousePoint? = null
    private var listed = false
    private var pane: TmuxClientPane? = null

    // The row of the drag's last report, and whether the drag scrolls on that row.
    private var lastRow = 0
    private var scrolling = false

    /**
     * Where to report a mouse [action] of [button] at [point], with cells of [cellWidth] by
     * [cellHeight] pixels.
     */
    fun confine(button: Int, action: Int, point: MousePoint, cellWidth: Int, cellHeight: Int): ConfinedMouse {
        if (button != TerminalMouseButton.Left || cellWidth <= 0 || cellHeight <= 0) return ConfinedMouse(point)
        when (action) {
            TerminalMouseAction.Press -> {
                forget()
                press = point
                return ConfinedMouse(point)
            }

            TerminalMouseAction.Motion -> {
                val start = press ?: return ConfinedMouse(point)
                if (!listed) {
                    listed = true
                    val col = floor(start.x / cellWidth).toInt()
                    lastRow = floor(start.y / cellHeight).toInt()
                    pane = listPanes()?.firstOrNull { it.rect.contains(col, lastRow) }
                }
            }
        }
        val inPane = pane
        if (action == TerminalMouseAction.Release) forget()
        if (inPane == null) return ConfinedMouse(point)
        val confined = keepIn(inPane.rect, point, cellWidth, cellHeight)
        if (action != TerminalMouseAction.Motion) return ConfinedMouse(confined)
        return ConfinedMouse(confined, edgeScroll(inPane, confined, point.y, cellHeight))
    }

    private fun forget() {
        press = null
        listed = false
        pane = null
        scrolling = false
    }

    private fun keepIn(pane: TmuxPaneRect, point: MousePoint, cellWidth: Int, cellHeight: Int): MousePoint {
        val left = (pane.x * cellWidth).toFloat()
        val top = (pane.y * cellHeight).toFloat()
        return MousePoint(
            point.x.coerceIn(left, left + pane.cols * cellWidth - 1f),
            point.y.coerceIn(top, top + pane.rows * cellHeight - 1f),
        )
    }

    /**
     * The scroll for a motion reported at [confined] when the pointer is at height [y], or
     * null when tmux shouldn't scroll: off the top and bottom rows of [pane], in a pane of
     * one row, which has no row next to it, and where the pane's program takes the drag.
     */
    private fun edgeScroll(pane: TmuxClientPane, confined: MousePoint, y: Float, cellHeight: Int): EdgeScroll? {
        val row = floor(confined.y / cellHeight).toInt()
        val cameToRow = row != lastRow
        lastRow = row
        val top = pane.rect.y
        val bottom = top + pane.rect.rows - 1
        if (!pane.dragSelects || top == bottom || (row != top && row != bottom)) {
            scrolling = false
            return null
        }
        // How far the pointer is into the edge row or past it, in rows: one at the pane's edge.
        val depth = if (row == top) top + 1 - y / cellHeight else y / cellHeight - bottom
        val pastPane = if (row == top) depth > 1f else depth >= 1f
        scrolling = scrolling || cameToRow || pastPane
        if (!scrolling) return null
        val inner = confined.copy(y = confined.y + if (row == top) cellHeight else -cellHeight)
        return EdgeScroll(inner, depth.toInt().coerceIn(1, MAX_EDGE_SCROLL_ROWS))
    }
}
