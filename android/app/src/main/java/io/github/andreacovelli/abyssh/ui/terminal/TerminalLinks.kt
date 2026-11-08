package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalCellArea
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot

// ASCII URL characters only, so box-drawing borders and quotes around a URL stay out.
private val URL_REGEX = Regex("""(?:(?:https?|ftp)://|www\.)[A-Za-z0-9\-._~:/?#\[\]@!$&()*+,;=%]+""")
private const val TRAILING_PUNCTUATION = ".,;:!?"

/**
 * The URL written on screen at [cellIndex], or null. A URL that reaches the end of a
 * row continues on the next one, as long lines wrap. In a split window only the pane
 * the cell is in is read, so a URL wraps at the pane's edge and stops there.
 */
fun TerminalSnapshot.urlAt(cellIndex: Int): String? {
    if (cellIndex !in 0 until cellCount) return null
    val area = paneAt(cellIndex)?.area ?: TerminalCellArea(0, 0, cols, rows)
    val target = (cellIndex / cols - area.row) * area.cols + (cellIndex % cols - area.col)
    val text = visibleText(area)
    for (match in URL_REGEX.findAll(text)) {
        if (match.range.first > target) break
        val url = trimUrl(match.value)
        if (target < match.range.first + url.length) {
            return if (url.startsWith("www.")) "https://$url" else url
        }
    }
    return null
}

/** One character per cell of [area], row by row. Cells that aren't plain characters become spaces. */
private fun TerminalSnapshot.visibleText(area: TerminalCellArea): String = buildString(area.cols * area.rows) {
    for (row in area.row until minOf(area.row + area.rows, rows)) {
        for (col in area.col until minOf(area.col + area.cols, cols)) {
            val i = row * cols + col
            val cp = codepointAt(i)
            val plain = cp in 0x21..0xFFFF && !Character.isSurrogate(cp.toChar()) && !isSpacerContinuation(i)
            append(if (plain) cp.toChar() else ' ')
        }
        // A pane cut off by the window's edge still takes its full width, so cells stay aligned.
        repeat(maxOf(0, area.col + area.cols - cols)) { append(' ') }
    }
}

/** Drops sentence punctuation after a URL and closing brackets that weren't opened in it. */
internal fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        url = when {
            last in TRAILING_PUNCTUATION -> url.dropLast(1)
            last == ')' && url.count { it == '(' } < url.count { it == ')' } -> url.dropLast(1)
            last == ']' && url.count { it == '[' } < url.count { it == ']' } -> url.dropLast(1)
            else -> return url
        }
    }
    return url
}
