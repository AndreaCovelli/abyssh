package io.github.andreacovelli.abyssh.service.multiplexer.tmux

/** A pane's size in cells. */
internal data class TmuxPaneSize(val cols: Int, val rows: Int)

/**
 * Reads a tmux window layout such as `eb8f,100x30,0,0{50x30,0,0,1,49x30,51,0,2}`:
 * a checksum, then cells of `WxH,X,Y` that end in a pane number or hold more cells in
 * `{}` (side by side) or `[]` (stacked).
 */
internal object TmuxLayout {
    /** The size of each pane in [layout] by pane number, or an empty map when it can't be read. */
    fun paneSizes(layout: String): Map<Int, TmuxPaneSize> {
        val body = layout.substringAfter(',', "")
        if (body.isEmpty()) return emptyMap()
        val sizes = mutableMapOf<Int, TmuxPaneSize>()
        val reader = Reader(body)
        return if (reader.cell(sizes) && reader.atEnd) sizes else emptyMap()
    }

    private class Reader(private val text: String) {
        private var pos = 0
        val atEnd: Boolean get() = pos == text.length

        fun cell(sizes: MutableMap<Int, TmuxPaneSize>): Boolean {
            val cols = number() ?: return false
            if (!take('x')) return false
            val rows = number() ?: return false
            if (!take(',') || number() == null || !take(',') || number() == null) return false
            return when {
                take(',') -> {
                    val pane = number() ?: return false
                    sizes[pane] = TmuxPaneSize(cols, rows)
                    true
                }
                take('{') -> children(sizes, '}')
                take('[') -> children(sizes, ']')
                else -> false
            }
        }

        private fun children(sizes: MutableMap<Int, TmuxPaneSize>, close: Char): Boolean {
            do {
                if (!cell(sizes)) return false
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
