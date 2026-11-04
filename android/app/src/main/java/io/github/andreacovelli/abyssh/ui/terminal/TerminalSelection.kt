package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Selected cells from [anchorIndex] to [focusIndex], in reading order, or with
 * [rectangle] the block of cells whose opposite corners they are.
 */
data class TerminalSelection(
    val anchorIndex: Int,
    val focusIndex: Int,
    val rectangle: Boolean = false,
) {
    /** The cells from the first selected row's start cell to the last row's end cell. */
    fun normalized(cellCount: Int): IntRange? {
        if (cellCount <= 0) return null
        val start = minOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        val end = maxOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        return start..end
    }

    /** The columns a [rectangle] selection covers in every row, or null for a linear one. */
    fun columns(cols: Int): IntRange? {
        if (!rectangle || cols <= 0) return null
        val anchorCol = Math.floorMod(anchorIndex, cols)
        val focusCol = Math.floorMod(focusIndex, cols)
        return minOf(anchorCol, focusCol)..maxOf(anchorCol, focusCol)
    }

    /** The first selected cell: the top-left corner of a [rectangle]. */
    fun startCell(cols: Int): Int {
        val first = minOf(anchorIndex, focusIndex)
        val columns = columns(cols) ?: return first
        return first - Math.floorMod(first, cols) + columns.first
    }

    /** The last selected cell: the bottom-right corner of a [rectangle]. */
    fun endCell(cols: Int): Int {
        val last = maxOf(anchorIndex, focusIndex)
        val columns = columns(cols) ?: return last
        return last - Math.floorMod(last, cols) + columns.last
    }

    /**
     * Moves the start handle to [cell]. A [rectangle] keeps its bottom-right corner, so
     * the handles stay on the corners they're drawn at.
     */
    fun withStartHandle(cell: Int, cols: Int): TerminalSelection = if (rectangle) {
        copy(anchorIndex = cell, focusIndex = endCell(cols))
    } else {
        withStart(cell, updateAnchor = anchorIndex <= focusIndex)
    }

    /** Moves the end handle to [cell]; a [rectangle] keeps its top-left corner. */
    fun withEndHandle(cell: Int, cols: Int): TerminalSelection = if (rectangle) {
        copy(anchorIndex = startCell(cols), focusIndex = cell)
    } else {
        withEnd(cell, updateAnchor = anchorIndex > focusIndex)
    }

    fun withStart(newStartCell: Int, updateAnchor: Boolean): TerminalSelection =
        if (updateAnchor) {
            copy(anchorIndex = newStartCell)
        } else {
            copy(focusIndex = newStartCell)
        }

    fun withEnd(newEndCell: Int, updateAnchor: Boolean): TerminalSelection =
        if (updateAnchor) {
            copy(anchorIndex = newEndCell)
        } else {
            copy(focusIndex = newEndCell)
        }
}

data class TerminalSelectionState(
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsRight: Float,
    val boundsBottom: Float,
    /** Top-left px of the start cell, canvas-local. */
    val startOffset: Offset,
    /** Bottom-right px of the end cell (right edge + bottom edge), canvas-local. */
    val endOffset: Offset,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val cols: Int,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val text: String?,
)

/**
 * The characters that end a word, besides blank cells: Ghostty's default
 * selection-word-chars.
 */
const val DEFAULT_WORD_SEPARATORS = " \t'\"│`|:;,()[]{}<>$"

/**
 * The word at [cellIndex], within its row. A word is a run of cells that aren't blank
 * and whose characters aren't in [separators]; on a separator, the run of separators
 * around it. Null on a blank cell.
 */
internal fun TerminalSnapshot.wordAt(cellIndex: Int, separators: String = DEFAULT_WORD_SEPARATORS): IntRange? {
    if (cols <= 0 || cellIndex !in 0 until cellCount) return null
    val rowStart = cellIndex / cols * cols
    val rowEnd = rowStart + cols - 1
    if (isBlankCell(cellIndex)) return null
    val onSeparator = isSeparatorCell(cellIndex, separators)
    fun sameKind(index: Int) = !isBlankCell(index) && isSeparatorCell(index, separators) == onSeparator

    var start = cellIndex
    while (start > rowStart && sameKind(start - 1)) start--
    var end = cellIndex
    while (end < rowEnd && sameKind(end + 1)) end++
    return start..end
}

private fun TerminalSnapshot.isBlankCell(index: Int): Boolean {
    if (isSpacerContinuation(index)) return false
    val cp = codepointAt(index)
    return cp == 0 || cp == ' '.code
}

// The second half of a wide character goes with its first half.
private fun TerminalSnapshot.isSeparatorCell(index: Int, separators: String): Boolean {
    val owner = if (isSpacerContinuation(index) && index > 0) index - 1 else index
    val cp = codepointAt(owner)
    return cp != 0 && Character.isValidCodePoint(cp) && separators.indexOf(String(Character.toChars(cp))) >= 0
}

/**
 * The text of cells [range], or with [columns] only those columns of each row in it, as
 * a rectangle selection copies them. Trailing blanks in each row are dropped.
 */
internal fun extractSelectionText(snapshot: TerminalSnapshot, range: IntRange, columns: IntRange? = null): String? {
    if (snapshot.cols <= 0 || snapshot.cellCount == 0) return null

    val normalizedRange = IntRange(
        start = range.first.coerceIn(0, snapshot.cellCount - 1),
        endInclusive = range.last.coerceIn(0, snapshot.cellCount - 1),
    )
    val startRow = normalizedRange.first / snapshot.cols
    val endRow = normalizedRange.last / snapshot.cols
    val builder = StringBuilder(normalizedRange.last - normalizedRange.first + 1 + (endRow - startRow))

    for (row in startRow..endRow) {
        val rowStart = row * snapshot.cols
        val from = if (columns != null) rowStart + columns.first else maxOf(normalizedRange.first, rowStart)
        val until = if (columns != null) {
            rowStart + minOf(columns.last, snapshot.cols - 1)
        } else {
            minOf(normalizedRange.last, rowStart + snapshot.cols - 1)
        }
        var lastContentIdx = until
        while (lastContentIdx >= from) {
            val cp = snapshot.codepointAt(lastContentIdx)
            if (cp != 0 && (cp != 32 || snapshot.isSpacerContinuation(lastContentIdx))) break
            lastContentIdx--
        }
        for (index in from..lastContentIdx) {
            val codepoint = snapshot.codepointAt(index)
            if (codepoint == 0 || (codepoint == 32 && !snapshot.isSpacerContinuation(index))) {
                builder.append(' ')
            } else if (codepoint != 32) {
                builder.append(snapshot.glyphAt(index))
            }
        }
        if (row != endRow) {
            builder.append('\n')
        }
    }

    return builder.toString()
}

/**
 * Reads the text of screen cells screenStart..screenEnd, counted from the oldest
 * scrollback row, or of visible cells visibleStart..visibleEnd when that fails. Null
 * when the terminal can't, and the text is then taken from the snapshot.
 */
typealias SelectionTextReader =
    suspend (screenStart: Int, screenEnd: Int, visibleStart: Int, visibleEnd: Int, rectangle: Boolean) -> String?

/** The text under [selection], read through [reader] when there is one. */
internal suspend fun readSelectionText(
    snapshot: TerminalSnapshot,
    selection: TerminalSelection,
    reader: SelectionTextReader?,
): String? {
    val visibleRange = selection.normalized(snapshot.cellCount) ?: return null
    val columns = selection.columns(snapshot.cols)
    if (reader == null || snapshot.cols <= 0) return extractSelectionText(snapshot, visibleRange, columns)
    val screenOffset = snapshot.viewportScrollY * snapshot.cols
    // A rectangle's corners are its start and end cells, so the same two cells
    // describe either kind of selection to the terminal.
    val screenStart = (selection.startCell(snapshot.cols) + screenOffset).coerceAtLeast(0)
    val screenEnd = (selection.endCell(snapshot.cols) + screenOffset).coerceAtLeast(screenStart)
    val lastCell = snapshot.cellCount - 1
    val visibleStart = selection.startCell(snapshot.cols).coerceIn(0, lastCell)
    val visibleEnd = selection.endCell(snapshot.cols).coerceIn(visibleStart, lastCell)
    return reader(screenStart, screenEnd, visibleStart, visibleEnd, selection.rectangle)
        ?: extractSelectionText(snapshot, visibleRange, columns)
}

internal fun buildSelectionState(
    snapshot: TerminalSnapshot,
    selection: TerminalSelection,
    cellWidth: Float,
    cellHeight: Float,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    text: String?,
): TerminalSelectionState? {
    val cols = max(snapshot.cols, 1)
    val cellCount = snapshot.cellCount

    val visibleRange = selection.normalized(cellCount) ?: return null
    val columns = selection.columns(cols)
    val startCol = columns?.first ?: (visibleRange.first % cols)
    val startRow = visibleRange.first / cols
    val endCol = columns?.last ?: (visibleRange.last % cols)
    val endRow = visibleRange.last / cols
    val fullWidth = cols * cellWidth
    val boundsLeft = if (startRow == endRow || columns != null) startCol * cellWidth else 0f
    val boundsRight = if (startRow == endRow || columns != null) (endCol + 1) * cellWidth else fullWidth
    val boundsTop = startRow * cellHeight
    val boundsBottom = (endRow + 1) * cellHeight

    return TerminalSelectionState(
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
        startOffset = Offset(startCol * cellWidth, startRow * cellHeight),
        endOffset = Offset((endCol + 1) * cellWidth, (endRow + 1) * cellHeight),
        cellWidthPx = cellWidth,
        cellHeightPx = cellHeight,
        cols = cols,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        text = text,
    )
}

@Composable
internal fun TerminalSelectionHandle(
    tipX: Float,
    tipY: Float,
    color: Color,
    borderColor: Color,
    cellWidthPx: Float,
    cellHeightPx: Float,
    cols: Int,
    startCellProvider: () -> Int,
    onDragToCell: (newCell: Int) -> Unit,
) {
    val density = LocalDensity.current
    val touchSizePx = with(density) { 44.dp.toPx() }
    val ballRadiusPx = with(density) { 9.dp.toPx() }
    val stemPx = with(density) { 6.dp.toPx() }
    val originX = (tipX - touchSizePx / 2f).roundToInt()
    val originY = (tipY - touchSizePx).roundToInt()

    Box(
        modifier =
            Modifier
                .offset { IntOffset(originX, originY) }
                .size(44.dp)
                .pointerInput(cellWidthPx, cellHeightPx, cols) {
                    var accumX = 0f
                    var accumY = 0f
                    var startCell = 0
                    detectDragGestures(
                        onDragStart = {
                            accumX = 0f
                            accumY = 0f
                            startCell = startCellProvider()
                        },
                        onDrag = { _, dragAmount ->
                            if (cellWidthPx <= 0f || cellHeightPx <= 0f || cols <= 0) return@detectDragGestures
                            accumX += dragAmount.x
                            accumY += dragAmount.y
                            val dCol = (accumX / cellWidthPx).roundToInt()
                            val dRow = (accumY / cellHeightPx).roundToInt()
                            val newCell = (startCell + dRow * cols + dCol).coerceAtLeast(0)
                            onDragToCell(newCell)
                        },
                    )
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val ballCenterY = size.height - ballRadiusPx - stemPx
            val ballCenter = Offset(cx, ballCenterY)
            drawLine(
                color = color,
                start = Offset(cx, ballCenterY + ballRadiusPx * 0.6f),
                end = Offset(cx, size.height),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(color = borderColor, radius = ballRadiusPx + 1.dp.toPx(), center = ballCenter)
            drawCircle(color = color, radius = ballRadiusPx, center = ballCenter)
        }
    }
}
