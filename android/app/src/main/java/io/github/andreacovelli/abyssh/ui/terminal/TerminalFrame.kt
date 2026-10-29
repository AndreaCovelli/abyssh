package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot

internal const val TEXT_STYLE_MASK: Int =
    TerminalSnapshot.CELL_FLAG_BOLD or
        TerminalSnapshot.CELL_FLAG_ITALIC or
        TerminalSnapshot.CELL_FLAG_UNDERLINE or
        TerminalSnapshot.CELL_FLAG_FAINT

/** One frame to draw: the snapshot, the selected cells, and the visible search hits by row. */
internal class TerminalFrame(
    val snapshot: TerminalSnapshot,
    val cell: TerminalCellMetrics,
    val colors: TerminalCanvasColors,
    val selection: IntRange? = null,
    // Flagged true for the current match.
    val searchSpans: Map<Int, List<Pair<SearchSpan, Boolean>>> = emptyMap(),
) {
    /** The selected columns of [row], or null when none are. */
    fun selectedCols(row: Int): IntRange? {
        val selection = selection ?: return null
        val rowStart = row * snapshot.cols
        val first = maxOf(selection.first, rowStart)
        val last = minOf(selection.last, rowStart + snapshot.cols - 1)
        return if (first <= last) (first - rowStart)..(last - rowStart) else null
    }

    /** True when [row] has selected cells or search hits, which change without its cells. */
    fun hasHighlights(row: Int): Boolean = selectedCols(row) != null || !searchSpans[row].isNullOrEmpty()

    fun row(row: Int): FrameRow = FrameRow(snapshot.rowData[row], colors, selectedCols(row))
}

/** One row of a frame, its cells by column with the selection applied. */
internal class FrameRow(val cells: TerminalRow, private val colors: TerminalCanvasColors, private val selected: IntRange? = null) {
    val cols: Int get() = cells.codepoints.size

    fun isSelected(col: Int): Boolean = selected?.contains(col) == true

    fun isInverse(col: Int): Boolean =
        !isSelected(col) && (cells.flags[col].toInt() and TerminalSnapshot.CELL_FLAG_INVERSE) != 0

    fun backgroundAt(col: Int): Int = when {
        isSelected(col) -> colors.selectionBackground
        isInverse(col) -> cells.fgArgb[col]
        else -> cells.bgArgb[col]
    }

    fun foregroundAt(col: Int): Int {
        val selectedForeground = colors.selectionForeground
        return when {
            selectedForeground != null && isSelected(col) -> selectedForeground
            isInverse(col) -> cells.bgArgb[col]
            else -> cells.fgArgb[col]
        }
    }

    fun textStyleAt(col: Int): Int = cells.flags[col].toInt() and TEXT_STYLE_MASK

    /** The first column after the run of matching backgrounds that starts at [start]. */
    fun backgroundRunEnd(start: Int): Int {
        val background = backgroundAt(start)
        val inverse = isInverse(start)
        var end = start + 1
        while (end < cols && backgroundAt(end) == background && isInverse(end) == inverse) end++
        return end
    }

    /**
     * The first column after the text run that starts at [start]: text drawn in one call,
     * with one color, style, and paint. Spaces join a run; empty cells and wide
     * characters after the first end it.
     */
    inline fun textRunEnd(start: Int, paintChoiceAt: (Int) -> Int): Int {
        val foreground = foregroundAt(start)
        val style = textStyleAt(start)
        val choice = paintChoiceAt(start)
        var end = textRunBodyStart(start)
        while (end < cols &&
            joinsTextRun(end, foreground, style) &&
            (cells.codepoints[end] == ' '.code || paintChoiceAt(end) == choice)
        ) {
            end++
        }
        return end
    }

    /** Where a run's later cells start: past the first cell's spacer when it is wide. */
    fun textRunBodyStart(start: Int): Int =
        if (start + 1 < cols && cells.isSpacerContinuation(start + 1)) start + 2 else start + 1

    fun joinsTextRun(col: Int, foreground: Int, style: Int): Boolean {
        val codepoint = cells.codepoints[col]
        return when {
            codepoint == 0 -> false
            codepoint == ' '.code && !cells.isSpacerContinuation(col) -> false
            col + 1 < cols && cells.isSpacerContinuation(col + 1) -> false
            else -> foregroundAt(col) == foreground && textStyleAt(col) == style
        }
    }
}
