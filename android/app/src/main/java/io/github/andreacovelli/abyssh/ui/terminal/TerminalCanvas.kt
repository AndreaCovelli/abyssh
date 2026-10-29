package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive

/**
 * Draws a terminal snapshot and turns touch, mouse, and pinch input into selections,
 * clicks, scrolling, and font size changes. Fonts and cell metrics live in
 * [TerminalFonts], drawing in [TerminalRenderer], and input in [TerminalCanvasGestures].
 *
 * [snapshot] is read in the draw phase and in effects, not during composition, so a
 * snapshot held in Compose state redraws the canvas without recomposing it.
 */
@Composable
fun TerminalCanvas(
    snapshot: () -> TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    fitSnapshotToCanvas: Boolean = false,
    enableGestures: Boolean = true,
    cursorColor: Color = Color.White.copy(alpha = 0.28f),
    cursorTextColor: Color? = null,
    selectionBackgroundColor: Color = Color(0x663B82F6),
    selectionForegroundColor: Color? = null,
    // Hoisted: the host owns selection state so it can render drag handles
    // and mutate either endpoint independently.
    selection: TerminalSelection? = null,
    onSelectionChange: (TerminalSelection?) -> Unit = {},
    // Reads the selected text from the terminal; without one it comes from the snapshot.
    selectionText: SelectionTextReader? = null,
    onResize: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _, _ -> },
    onTap: () -> Unit = {},
    onPrimaryClick: (x: Float, y: Float) -> Unit = { _, _ -> },
    // A tap where a link may be: when the app doesn't take mouse input, or with Shift.
    // textUrl is the URL written at the cell; noLink runs the usual tap when there's none.
    onLinkTap: (col: Int, row: Int, textUrl: String?, noLink: () -> Unit) -> Unit =
        { _, _, _, noLink -> noLink() },
    // Two-finger tap. Sent as a right click to apps that take mouse input.
    onSecondaryClick: (x: Float, y: Float) -> Unit = { _, _ -> },
    // Right or middle button of a physical mouse: press, motion and release.
    onMouseButton: (button: Int, action: Int, x: Float, y: Float) -> Unit = { _, _, _, _ -> },
    onAppSelectionDrag: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    // Shift held (accessory bar or hardware keyboard) makes a long-press select text
    // even when the remote app takes mouse input, like Shift+drag in desktop terminals.
    shiftActive: Boolean = false,
    onShiftSelectionStart: () -> Unit = {},
    onScroll: (delta: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    minFontSizeSp: Float = 1f,
    maxFontSizeSp: Float = Float.MAX_VALUE,
    onFontSizeChange: (sizeSp: Float) -> Unit = {},
    onSelectionChanged: (TerminalSelectionState?) -> Unit = {},
    // Scrollback search hits, painted behind the text; the current one stands out.
    searchMatches: List<SearchMatch> = emptyList(),
    currentSearchMatch: SearchMatch? = null,
    searchMatchColor: Color = Color(0x66FACC15),
    currentSearchMatchColor: Color = Color(0xCCF97316),
) {
    val fonts = rememberTerminalFonts()
    val fontSizePx = with(LocalDensity.current) { fontSizeSp.sp.toPx() }
    val paints = remember(fonts, fontSizePx) { TerminalTextPaints(fonts, fontSizePx) }
    val cell = remember(paints) { paints.cellMetrics() }
    val renderer = remember(fonts) { TerminalRenderer(fonts) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val snapshotSource = remember { { currentSnapshot() } }

    val callbacks = TerminalCanvasCallbacks(
        onSelectionChange = onSelectionChange,
        onTap = onTap,
        onPrimaryClick = onPrimaryClick,
        onLinkTap = onLinkTap,
        onSecondaryClick = onSecondaryClick,
        onMouseButton = onMouseButton,
        onAppSelectionDrag = onAppSelectionDrag,
        onShiftSelectionStart = onShiftSelectionStart,
        onFontSizeChange = onFontSizeChange,
    )
    val env = rememberUpdatedState(
        TerminalGestureEnv(
            snapshotSource = snapshotSource,
            fitSnapshotToCanvas = fitSnapshotToCanvas,
            selection = selection,
            cell = cell,
            canvasSize = canvasSize,
            fontSizeSp = fontSizeSp,
            fontSizeRange = minFontSizeSp..maxFontSizeSp,
            shiftActive = shiftActive,
            haptics = LocalHapticFeedback.current,
            callbacks = callbacks,
        ),
    )
    val scrollDeltas = rememberScrollDeltas(onScroll)
    val gestureConfig = rememberTerminalGestureConfig()
    val gestures = remember(gestureConfig) {
        TerminalCanvasGestures(env, gestureConfig) { scrollDeltas.trySend(it) }
    }

    TerminalSelectionEffects(
        snapshotSource,
        selection,
        cell,
        canvasSize,
        selectionText,
        gestures,
        onSelectionChange,
        onSelectionChanged,
    )
    TerminalResizeEffect(canvasSize, cell, onResize)

    val searchSpans = remember { SearchSpanCache() }
    val colors = TerminalCanvasColors(
        selectionBackground = selectionBackgroundColor.toArgb(),
        selectionForeground = selectionForegroundColor?.toArgb(),
        searchMatch = searchMatchColor.toArgb(),
        currentSearchMatch = currentSearchMatchColor.toArgb(),
        cursor = cursorColor.toArgb(),
        cursorText = cursorTextColor?.toArgb(),
    )
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .then(if (enableGestures) gestures.modifier else Modifier),
    ) {
        val shown = snapshotSource()
        drawRect(color = Color(shown.defaultBgArgb))
        val placement = SnapshotPlacement.of(fitSnapshotToCanvas, shown, cell, size.width, size.height)
        val frame = TerminalFrame(
            snapshot = shown,
            cell = cell,
            colors = colors,
            selection = selection?.normalized(shown.cellCount),
            searchSpans = searchSpans.get(searchMatches, currentSearchMatch, shown),
        )
        drawIntoCanvas { renderer.draw(it.nativeCanvas, frame, paints, placement) }
    }
}

/** Scroll requests, merged so the app gets at most one per frame. */
@Composable
private fun rememberScrollDeltas(onScroll: (delta: Int, x: Float, y: Float) -> Unit): Channel<TerminalScrollDelta> {
    val currentOnScroll by rememberUpdatedState(onScroll)
    val channel = remember { Channel<TerminalScrollDelta>(capacity = Channel.UNLIMITED) }
    LaunchedEffect(channel) {
        while (isActive) {
            var latest = channel.receive()
            var accumulated = latest.delta
            generateSequence { channel.tryReceive().getOrNull() }.forEach { next ->
                accumulated += next.delta
                latest = next
            }
            if (accumulated != 0) currentOnScroll(accumulated, latest.x, latest.y)
            withFrameNanos { }
        }
    }
    return channel
}

/**
 * Reports the selection's on-screen state, and keeps the selection on the same text
 * while the viewport scrolls under it.
 */
@Composable
private fun TerminalSelectionEffects(
    snapshot: () -> TerminalSnapshot,
    selection: TerminalSelection?,
    cell: TerminalCellMetrics,
    canvasSize: IntSize,
    selectionText: SelectionTextReader?,
    gestures: TerminalCanvasGestures,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onSelectionChanged: (TerminalSelectionState?) -> Unit,
) {
    val currentOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentSelection by rememberUpdatedState(selection)
    val currentSelectionText by rememberUpdatedState(selectionText)
    if (selection != null) {
        LaunchedEffect(selection, cell, canvasSize) {
            // The text is read on the session thread, which may be busy, so skip to the
            // newest snapshot rather than queue one read per frame.
            snapshotFlow { snapshot() }.conflate().collect { shown ->
                currentOnSelectionChanged(
                    buildSelectionState(
                        snapshot = shown,
                        selection = selection,
                        cellWidth = cell.width,
                        cellHeight = cell.height,
                        canvasWidthPx = canvasSize.width,
                        canvasHeightPx = canvasSize.height,
                        text = readSelectionText(shown, selection, currentSelectionText),
                    ),
                )
            }
        }
    } else {
        LaunchedEffect(Unit) { currentOnSelectionChanged(null) }
    }

    // Keeps the selection on the same text while the viewport scrolls under it.
    val hasSelection = selection != null
    LaunchedEffect(hasSelection) {
        if (!hasSelection) return@LaunchedEffect
        var baseline = snapshot().viewportScrollY
        snapshotFlow { snapshot().let { it.viewportScrollY to it.cols } }.collect { (scrollY, cols) ->
            if (scrollY == baseline) return@collect
            val deltaCells = (baseline - scrollY) * max(cols, 1)
            baseline = scrollY
            val sel = currentSelection ?: return@collect
            currentOnSelectionChange(sel.shiftedBy(deltaCells, focusToo = !gestures.autoScrollingSelection))
        }
    }
}

/** Tells the terminal its grid size whenever the canvas or cell size changes it. */
@Composable
private fun TerminalResizeEffect(
    canvasSize: IntSize,
    cell: TerminalCellMetrics,
    onResize: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) -> Unit,
) {
    var lastResizedGrid by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(canvasSize, cell) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = max(1, floor(canvasSize.width / cell.width).toInt())
        val rows = max(1, floor(canvasSize.height / cell.height).toInt())
        val grid = cols to rows
        if (grid != lastResizedGrid) {
            lastResizedGrid = grid
            onResize(cols, rows, cell.widthPx, cell.heightPx, canvasSize.width, canvasSize.height)
        }
    }
}

/**
 * The visible parts of the search matches, by viewport row, flagged when current. Kept
 * until the matches or the viewport change, since the canvas asks on every frame.
 */
private class SearchSpanCache {
    private var matches: List<SearchMatch>? = null
    private var current: SearchMatch? = null
    private var viewport = Triple(-1, -1, -1)
    private var spans: Map<Int, List<Pair<SearchSpan, Boolean>>> = emptyMap()

    fun get(matches: List<SearchMatch>, current: SearchMatch?, snapshot: TerminalSnapshot): Map<Int, List<Pair<SearchSpan, Boolean>>> {
        val viewport = Triple(snapshot.viewportScrollY, snapshot.rows, snapshot.cols)
        if (matches !== this.matches || current != this.current || viewport != this.viewport) {
            this.matches = matches
            this.current = current
            this.viewport = viewport
            spans = matches
                .flatMap { match ->
                    match.viewportSpans(snapshot.viewportScrollY, snapshot.rows, snapshot.cols).map { span -> span to (match == current) }
                }
                .groupBy { it.first.row }
        }
        return spans
    }
}

private fun TerminalSelection.shiftedBy(deltaCells: Int, focusToo: Boolean): TerminalSelection =
    copy(anchorIndex = anchorIndex + deltaCells, focusIndex = if (focusToo) focusIndex + deltaCells else focusIndex)
