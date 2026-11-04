package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalCellArea

/**
 * A search hit in screen coordinates. Rows count from the oldest scrollback row, like
 * [TerminalSnapshot.viewportScrollY]. The end cell is included.
 */
data class SearchMatch(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

/** One row's worth of a match on screen: viewport row and the first and last column. */
data class SearchSpan(val row: Int, val firstCol: Int, val lastCol: Int)

/** Decodes the native search result: four ints per match. */
fun parseSearchMatches(raw: IntArray): List<SearchMatch> =
    List(raw.size / 4) { i -> SearchMatch(raw[i * 4], raw[i * 4 + 1], raw[i * 4 + 2], raw[i * 4 + 3]) }

/**
 * The parts of this match inside a viewport whose top row is [viewportTop], kept inside
 * [area] when the viewport shows several terminals and the match is in the one there.
 */
fun SearchMatch.viewportSpans(viewportTop: Int, rows: Int, cols: Int, area: TerminalCellArea? = null): List<SearchSpan> {
    val bounds = area ?: TerminalCellArea(0, 0, cols, rows)
    val leftCol = bounds.col.coerceAtLeast(0)
    val rightCol = minOf(bounds.col + bounds.cols, cols) - 1
    if (leftCol > rightCol) return emptyList()
    val first = maxOf(startRow, viewportTop + bounds.row)
    val last = minOf(endRow, viewportTop + minOf(bounds.row + bounds.rows, rows) - 1)
    if (first > last) return emptyList()
    return (first..last).map { row ->
        SearchSpan(
            row = row - viewportTop,
            firstCol = if (row == startRow) startCol.coerceIn(leftCol, rightCol) else leftCol,
            lastCol = if (row == endRow) endCol.coerceIn(leftCol, rightCol) else rightCol,
        )
    }
}

/**
 * The open search bar. [matches] are newest first, as the native search returns them;
 * [current] indexes into them and is -1 when nothing matched.
 */
data class TerminalSearchState(
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val current: Int = -1,
) {
    val currentMatch: SearchMatch? get() = matches.getOrNull(current)

    /** The current match's number, counted top to bottom like the output itself; 0 for none. */
    val matchNumber: Int get() = if (current in matches.indices) matches.size - current else 0

    /** The next match up, toward older output. Wraps to the newest. */
    fun older(): TerminalSearchState =
        if (matches.isEmpty()) this else copy(current = (current + 1).mod(matches.size))

    /** The next match down, toward newer output. Wraps to the oldest. */
    fun newer(): TerminalSearchState =
        if (matches.isEmpty()) this else copy(current = (current - 1).mod(matches.size))

    /** New results for the same query: stay on the current match when it's still there. */
    fun withMatches(found: List<SearchMatch>): TerminalSearchState {
        val kept = currentMatch?.let(found::indexOf) ?: -1
        return copy(matches = found, current = if (kept >= 0) {
            kept
        } else if (found.isEmpty()) {
            -1
        } else {
            0
        })
    }
}
