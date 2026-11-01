package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneRect
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout

// How much of the text color the borders away from the active pane get; the rest is background.
private const val INACTIVE_BORDER_WEIGHT = 0.35f
private const val CHANNEL_MASK = 0xFF
private const val OPAQUE = 0xFF shl 24

// Box-drawing glyphs by which neighbours are border too: up 8, down 4, left 2, right 1.
private val BORDER_GLYPHS = intArrayOf(
    ' '.code, '─'.code, '─'.code, '─'.code,
    '│'.code, '┌'.code, '┐'.code, '┬'.code,
    '│'.code, '└'.code, '┘'.code, '┴'.code,
    '│'.code, '├'.code, '┤'.code, '┼'.code,
)

/**
 * Draws a split tmux window as one snapshot: each pane's rows in its place, the cursor
 * and mouse modes of the active pane, and the cells between panes as box-drawing lines,
 * brighter around the active pane. A window row whose pane rows are the same objects as
 * last time keeps its row object, so the renderer's per-row caches keep working.
 */
internal class TmuxWindowCompositor {
    private var layout: TmuxWindowLayout? = null
    private var activePane = -1
    private var colors = 0L

    // The border glyph of each cell of the layout, 0 where a pane is.
    private var borders = IntArray(0)

    // The pane rows each window row was built from, and the rows built.
    private var sources: List<Array<TerminalRow?>> = emptyList()
    private var rows: List<TerminalRow> = emptyList()

    /**
     * The window [layout] with [panes]' snapshots, by pane id, in place, or null before
     * any pane has one. A window of one pane is that pane's snapshot as it is.
     */
    fun compose(layout: TmuxWindowLayout, panes: Map<Int, TerminalSnapshot>, activePane: Int): TerminalSnapshot? {
        val single = layout.panes.singleOrNull()
        if (single != null && single.x == 0 && single.y == 0 && single.cols == layout.cols && single.rows == layout.rows) {
            this.layout = null
            return panes[single.paneId]
        }
        val active = panes[activePane]
        val base = active ?: layout.panes.firstNotNullOfOrNull { panes[it.paneId] } ?: return null
        val colors = (base.defaultBgArgb.toLong() shl Int.SIZE_BITS) or (base.defaultFgArgb.toLong() and 0xFFFFFFFFL)
        if (layout != this.layout) borders = borderGlyphs(layout)
        val sameFrame = layout == this.layout && activePane == this.activePane && colors == this.colors
        this.layout = layout
        this.activePane = activePane
        this.colors = colors

        val activeRect = layout.pane(activePane)
        val frame = Frame(layout, panes, activeRect, base)
        val newSources = List(layout.rows) { row ->
            Array(layout.panes.size) { i ->
                val rect = layout.panes[i]
                if (row in rect.y until rect.y + rect.rows) panes[rect.paneId]?.rowData?.getOrNull(row - rect.y) else null
            }
        }
        val rowData = List(layout.rows) { row ->
            val previous = if (sameFrame) rows.getOrNull(row) else null
            val old = sources.getOrNull(row)
            if (previous != null && old != null && sameRows(old, newSources[row])) previous else frame.row(row, newSources[row])
        }
        sources = newSources
        rows = rowData
        return snapshotOf(layout, panes, rowData, active, activeRect, base)
    }

    /** The window's snapshot from its [rowData], with the cursor, mouse modes, and scroll of [active]. */
    private fun snapshotOf(
        layout: TmuxWindowLayout,
        panes: Map<Int, TerminalSnapshot>,
        rowData: List<TerminalRow>,
        active: TerminalSnapshot?,
        activeRect: TmuxPaneRect?,
        base: TerminalSnapshot,
    ): TerminalSnapshot {
        val cursorInPane = active != null &&
            activeRect != null &&
            active.cursorVisible &&
            active.cursorX < activeRect.cols &&
            active.cursorY < activeRect.rows
        return TerminalSnapshot(
            cols = layout.cols,
            rows = layout.rows,
            cursorX = (active?.cursorX ?: 0) + (activeRect?.x ?: 0),
            cursorY = (active?.cursorY ?: 0) + (activeRect?.y ?: 0),
            cursorVisible = cursorInPane,
            cursorStyle = active?.cursorStyle ?: TerminalSnapshot.CURSOR_STYLE_BLOCK,
            defaultBgArgb = base.defaultBgArgb,
            defaultFgArgb = base.defaultFgArgb,
            rowData = rowData,
            images = layout.panes.flatMap { rect ->
                panes[rect.paneId]?.images.orEmpty().map { it.copy(cellCol = it.cellCol + rect.x, cellRow = it.cellRow + rect.y) }
            },
            viewportScrollY = active?.viewportScrollY ?: 0,
            appHandlesSelectionDrag = active?.appHandlesSelectionDrag == true,
            appTracksMouse = active?.appTracksMouse == true,
            activeArea = activeRect?.let { TerminalCellArea(it.x, it.y, it.cols, it.rows) },
        )
    }

    /** Builds the rows of one composition. */
    private inner class Frame(
        private val layout: TmuxWindowLayout,
        private val panes: Map<Int, TerminalSnapshot>,
        private val activeRect: TmuxPaneRect?,
        private val base: TerminalSnapshot,
    ) {
        private val activeBorder = base.defaultFgArgb
        private val inactiveBorder = blend(base.defaultFgArgb, base.defaultBgArgb, INACTIVE_BORDER_WEIGHT)

        fun row(row: Int, sources: Array<TerminalRow?>): TerminalRow {
            val width = layout.cols
            val codepoints = IntArray(width)
            val fg = IntArray(width) { base.defaultFgArgb }
            val bg = IntArray(width) { base.defaultBgArgb }
            val flags = ByteArray(width)
            val decorations = ByteArray(width)
            val underline = IntArray(width)
            val extras = mutableMapOf<Int, IntArray>()
            for (col in 0 until width) {
                val glyph = borders[row * width + col]
                if (glyph == 0) continue
                codepoints[col] = glyph
                fg[col] = if (activeRect?.bordersOn(col, row) == true) activeBorder else inactiveBorder
            }
            layout.panes.forEachIndexed { i, rect ->
                if (row !in rect.y until rect.y + rect.rows) return@forEachIndexed
                val end = minOf(rect.x + rect.cols, width)
                val source = sources[i]
                val copied = if (source == null) 0 else minOf(end - rect.x, source.codepoints.size)
                if (source != null) {
                    source.codepoints.copyInto(codepoints, rect.x, 0, copied)
                    source.fgArgb.copyInto(fg, rect.x, 0, copied)
                    source.bgArgb.copyInto(bg, rect.x, 0, copied)
                    source.flags.copyInto(flags, rect.x, 0, copied)
                    source.decorations.copyInto(decorations, rect.x, 0, copied)
                    source.underlineArgb.copyInto(underline, rect.x, 0, copied)
                    source.graphemeExtras.forEach { (col, extra) -> if (col < copied) extras[col + rect.x] = extra }
                }
                // Cells the pane hasn't drawn yet, as before its first capture lands.
                bg.fill(panes[rect.paneId]?.defaultBgArgb ?: base.defaultBgArgb, rect.x + copied, end)
            }
            return TerminalRow(codepoints, fg, bg, flags, extras, decorations, underline)
        }
    }

    private companion object {
        fun sameRows(a: Array<TerminalRow?>, b: Array<TerminalRow?>): Boolean =
            a.size == b.size && a.indices.all { a[it] === b[it] }

        /** The border glyph of each cell of [layout], 0 where a pane is. */
        fun borderGlyphs(layout: TmuxWindowLayout): IntArray {
            val width = layout.cols
            val height = layout.rows
            val covered = BooleanArray(width * height)
            layout.panes.forEach { rect ->
                for (row in rect.y until minOf(rect.y + rect.rows, height)) {
                    for (col in rect.x until minOf(rect.x + rect.cols, width)) covered[row * width + col] = true
                }
            }
            fun border(col: Int, row: Int) = col in 0 until width && row in 0 until height && !covered[row * width + col]
            return IntArray(width * height) { i ->
                val col = i % width
                val row = i / width
                if (covered[i]) {
                    0
                } else {
                    val up = if (border(col, row - 1)) 8 else 0
                    val down = if (border(col, row + 1)) 4 else 0
                    val left = if (border(col - 1, row)) 2 else 0
                    val right = if (border(col + 1, row)) 1 else 0
                    BORDER_GLYPHS[up or down or left or right]
                }
            }
        }

        /** Whether cell ([col], [row]) is on the ring of cells around this pane. */
        fun TmuxPaneRect.bordersOn(col: Int, row: Int): Boolean = col in x - 1..x + cols && row in y - 1..y + rows

        /** [weight] of [front] over the rest of [back], per channel, opaque. */
        fun blend(front: Int, back: Int, weight: Float): Int {
            fun channel(shift: Int): Int {
                val a = (front shr shift) and CHANNEL_MASK
                val b = (back shr shift) and CHANNEL_MASK
                return (a * weight + b * (1 - weight)).toInt() shl shift
            }
            return OPAQUE or channel(16) or channel(8) or channel(0)
        }
    }
}
