package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
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
    rowData[cellIndex / cols].isSpacerContinuation(cellIndex % cols)

/** The text in a cell: its codepoint plus any grapheme extras. */
internal fun TerminalSnapshot.glyphAt(cellIndex: Int): String = rowData[cellIndex / cols].glyphAt(cellIndex % cols)

/** True when a cell holds more than one codepoint. */
internal fun TerminalSnapshot.hasGraphemeExtras(cellIndex: Int): Boolean =
    rowData[cellIndex / cols].hasGraphemeExtras(cellIndex % cols)

/** True for the second cell of a wide character. */
internal fun TerminalRow.isSpacerContinuation(col: Int): Boolean =
    (flags[col].toInt() and TerminalSnapshot.CELL_FLAG_SPACER) != 0

/**
 * How many cells the glyph at [col] may fill: both cells of a wide character, or its own
 * and the next when that one is blank. A blank cell after a wide character is not counted.
 */
internal fun TerminalRow.glyphRoomCells(col: Int): Int {
    val next = col + 1
    if (next >= codepoints.size) return 1
    val codepoint = codepoints[next]
    return if (isSpacerContinuation(next) || codepoint == 0 || codepoint == ' '.code) 2 else 1
}

/** The text in a cell: its codepoint plus any grapheme extras. */
internal fun TerminalRow.glyphAt(col: Int): String {
    val codepoint = codepoints[col]
    val extras = graphemeExtras[col]
    if (extras == null || extras.isEmpty()) return String(Character.toChars(codepoint))
    val builder = StringBuilder(1 + extras.size)
    builder.appendCodePoint(codepoint)
    for (cp in extras) builder.appendCodePoint(cp)
    return builder.toString()
}

/** True when a cell holds more than one codepoint. */
internal fun TerminalRow.hasGraphemeExtras(col: Int): Boolean = graphemeExtras[col]?.isNotEmpty() == true
