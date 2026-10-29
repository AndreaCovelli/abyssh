package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.toSize
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot

private const val MAX_ZOOM = 6f

/** How far zoom mode magnifies when it starts, and when a double tap zooms in. */
private const val FOCUS_ZOOM = 2f

/**
 * Zoom mode: magnifies the drawn terminal without changing its font size, so the grid
 * and the remote terminal's size stay as they are. [scale] and [offset] place the
 * canvas, scaled from its top left corner, and always keep it covering its frame.
 */
@Stable
internal class TerminalZoomState {
    var active by mutableStateOf(false)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Counts scale changes, so the indicator shows again on each; 0 keeps it hidden. */
    var changes by mutableIntStateOf(0)
        private set

    // Set by [toggle] until the frame knows its size and zooms in on the cursor.
    private var pendingFocus = false

    fun toggle() {
        if (active) {
            exit()
        } else {
            active = true
            pendingFocus = true
        }
    }

    fun exit() {
        active = false
        pendingFocus = false
        scale = 1f
        offset = Offset.Zero
    }

    /** Zooms in on [focus] once, the first time the frame has a size after [toggle]. */
    fun focusOnEntry(focus: Offset, size: Size) {
        if (!pendingFocus || size.isEmpty()) return
        pendingFocus = false
        zoomOn(focus, size)
    }

    /** A pinch or drag: scales by [zoom] around [centroid], then moves by [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, size: Size) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        // Keeps the point under the fingers under them while the scale changes.
        place(newScale, centroid - (centroid - offset) * (newScale / scale) + pan, size)
    }

    /** A double tap: zooms in on [focus], or back out to the whole terminal. */
    fun toggleFocus(focus: Offset, size: Size) {
        if (scale > 1f) place(1f, Offset.Zero, size) else zoomOn(focus, size)
    }

    /** Keeps the canvas covering a frame that changed size. */
    fun fit(size: Size) = place(scale, offset, size)

    /** Magnifies [focus], given in unzoomed canvas pixels, and centers it. */
    private fun zoomOn(focus: Offset, size: Size) {
        place(FOCUS_ZOOM, size.center - focus * FOCUS_ZOOM, size)
    }

    private fun place(newScale: Float, newOffset: Offset, size: Size) {
        if (newScale != scale) changes++
        scale = newScale
        offset = Offset(
            newOffset.x.coerceIn(size.width * (1f - newScale), 0f),
            newOffset.y.coerceIn(size.height * (1f - newScale), 0f),
        )
    }
}

private val Size.center: Offset get() = Offset(width / 2f, height / 2f)

/** The middle of the cursor's cell in unzoomed canvas pixels, or the middle of the canvas. */
internal fun TerminalSnapshot.cursorFocus(size: Size): Offset {
    val onScreen = cursorVisible && cursorX in 0 until cols && cursorY in 0 until rows
    if (!onScreen) return size.center
    return Offset((cursorX + 0.5f) / cols * size.width, (cursorY + 0.5f) / rows * size.height)
}

/**
 * Draws [content] at [zoom]'s scale and offset. While zoom mode is on, a pinch zooms,
 * a drag pans, a double tap zooms in or out, a tap calls [onTap], and back leaves zoom
 * mode; the terminal underneath gets none of these touches.
 */
@Composable
internal fun TerminalZoomFrame(
    zoom: TerminalZoomState,
    snapshot: () -> TerminalSnapshot,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var size by remember { mutableStateOf(Size.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    BackHandler(enabled = zoom.active, onBack = zoom::exit)
    LaunchedEffect(zoom.active, size) {
        if (!zoom.active) return@LaunchedEffect
        zoom.fit(size)
        zoom.focusOnEntry(snapshot().cursorFocus(size), size)
    }
    Box(modifier = modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it.toSize() }) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        ) {
            content()
        }
        if (zoom.active) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(zoom) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            zoom.transform(centroid, pan, gestureZoom, size)
                        }
                    }
                    .pointerInput(zoom) {
                        detectTapGestures(
                            onTap = { currentOnTap() },
                            onDoubleTap = { zoom.toggleFocus(it, size) },
                        )
                    },
            )
        }
        TerminalZoomIndicator(
            text = stringResource(R.string.terminal_zoom_scale, zoom.scale),
            changes = zoom.changes,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
