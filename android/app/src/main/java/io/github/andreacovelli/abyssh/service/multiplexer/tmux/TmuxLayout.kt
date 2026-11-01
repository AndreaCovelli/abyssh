package io.github.andreacovelli.abyssh.service.multiplexer.tmux

/** A pane's size in cells. */
internal data class TmuxPaneSize(val cols: Int, val rows: Int)

/** Where pane [paneId] sits in its window, in cells from the window's top left. */
internal data class TmuxPaneRect(val paneId: Int, val x: Int, val y: Int, val cols: Int, val rows: Int) {
    val size: TmuxPaneSize get() = TmuxPaneSize(cols, rows)

    fun contains(col: Int, row: Int): Boolean = col in x until x + cols && row in y until y + rows
}

/** A window's size and its panes. The cells no pane covers are the borders between them. */
internal data class TmuxWindowLayout(val cols: Int, val rows: Int, val panes: List<TmuxPaneRect>) {
    fun pane(paneId: Int): TmuxPaneRect? = panes.firstOrNull { it.paneId == paneId }

    fun paneAt(col: Int, row: Int): TmuxPaneRect? = panes.firstOrNull { it.contains(col, row) }

    companion object {
        /** A window that holds only pane [paneId], of [size]. */
        fun single(paneId: Int, size: TmuxPaneSize): TmuxWindowLayout =
            TmuxWindowLayout(size.cols, size.rows, listOf(TmuxPaneRect(paneId, 0, 0, size.cols, size.rows)))
    }
}

/**
 * Reads a tmux window layout such as `eb8f,100x30,0,0{50x30,0,0,1,49x30,51,0,2}`:
 * a checksum, then cells of `WxH,X,Y` that end in a pane number or hold more cells in
 * `{}` (side by side) or `[]` (stacked).
 */
internal object TmuxLayout {
    /** The window [layout] describes, or null when it can't be read. */
    fun parse(layout: String): TmuxWindowLayout? {
        val body = layout.substringAfter(',', "")
        if (body.isEmpty()) return null
        val panes = mutableListOf<TmuxPaneRect>()
        val reader = Reader(body)
        val root = reader.cell(panes) ?: return null
        if (!reader.atEnd || panes.isEmpty()) return null
        return TmuxWindowLayout(root.cols, root.rows, panes)
    }

    private class Reader(private val text: String) {
        private var pos = 0
        val atEnd: Boolean get() = pos == text.length

        /** Reads one cell and the panes inside it into [panes]; returns the cell's size. */
        fun cell(panes: MutableList<TmuxPaneRect>): TmuxPaneSize? {
            val cols = number() ?: return null
            if (!take('x')) return null
            val rows = number() ?: return null
            if (!take(',')) return null
            val x = number() ?: return null
            if (!take(',')) return null
            val y = number() ?: return null
            val read = when {
                take(',') -> number()?.let { pane -> panes += TmuxPaneRect(pane, x, y, cols, rows) } != null
                take('{') -> children(panes, '}')
                take('[') -> children(panes, ']')
                else -> false
            }
            return if (read) TmuxPaneSize(cols, rows) else null
        }

        private fun children(panes: MutableList<TmuxPaneRect>, close: Char): Boolean {
            do {
                if (cell(panes) == null) return false
            } while (take(','))
            return take(close)
        }

        private fun number(): Int? {
            val start = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            return text.substring(start, pos).toIntOrNull()
        }

        private fun take(char: Char): Boolean {
            if (pos >= text.length || text[pos] != char) return false
            pos++
            return true
        }
    }
}
