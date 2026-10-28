package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.floor

/** The cell under a point in snapshot pixels, clamped to the grid; null for an empty grid. */
internal fun TerminalSnapshot.cellAt(x: Float, y: Float, cellWidth: Float, cellHeight: Float): Int? {
    if (cols <= 0 || rows <= 0 || cellWidth <= 0f || cellHeight <= 0f) return null
    val col = floor(x / cellWidth).toInt().coerceIn(0, cols - 1)
    val row = floor(y / cellHeight).toInt().coerceIn(0, rows - 1)
    return row * cols + col
}

/** True for the second cell of a wide character. */
internal fun TerminalSnapshot.isSpacerContinuation(cellIndex: Int): Boolean =
    ((flags[cellIndex].toInt() and 0xFF) and TerminalSnapshot.CELL_FLAG_SPACER) != 0

/** The text in a cell: its codepoint plus any grapheme extras. */
internal fun TerminalSnapshot.glyphAt(cellIndex: Int): String {
    val codepoint = codepoints[cellIndex]
    val extras = graphemeExtras[cellIndex]
    if (extras == null || extras.isEmpty()) return String(Character.toChars(codepoint))
    val builder = StringBuilder(1 + extras.size)
    builder.appendCodePoint(codepoint)
    for (cp in extras) builder.appendCodePoint(cp)
    return builder.toString()
}

/** True when a cell holds more than one codepoint. */
internal fun TerminalSnapshot.hasGraphemeExtras(cellIndex: Int): Boolean =
    graphemeExtras[cellIndex]?.isNotEmpty() == true
