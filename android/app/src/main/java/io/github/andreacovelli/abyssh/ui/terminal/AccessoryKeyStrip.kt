package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** A key of an [AccessoryKeyStrip]. One [setApart] has extra room before it. */
internal class StripKey(val setApart: Boolean = false, val content: @Composable () -> Unit)

/**
 * What the keys of a strip measure along it, in pixels: the [padding] on each side of a
 * key's content, the least a key takes ([minSize]), the [spacing] between keys, and the
 * extra [setApartGap] before a key that is set apart.
 */
internal data class KeyMetrics(val padding: Float, val minSize: Float, val spacing: Float, val setApartGap: Float) {
    /** These metrics moved toward [tight] by [fraction], from 0 (these) to 1 ([tight]). */
    fun squeezedTo(tight: KeyMetrics, fraction: Float) = KeyMetrics(
        padding = padding + (tight.padding - padding) * fraction,
        minSize = minSize + (tight.minSize - minSize) * fraction,
        spacing = spacing + (tight.spacing - spacing) * fraction,
        setApartGap = setApartGap + (tight.setApartGap - setApartGap) * fraction,
    )

    /** What a key whose content is [content] long takes along the strip. */
    fun keySize(content: Int): Int = max(minSize, content + 2 * padding).roundToInt()
}

/**
 * Where the keys of a strip go along it. [sizes] has each key's size, null for one that
 * shows nothing; [leads] the room before each, on top of the [spacing]; the first
 * [firstLineCount] keys are on the first line.
 */
internal class StripPlan(val sizes: List<Int?>, val leads: List<Int>, val spacing: Int, val firstLineCount: Int)

/**
 * Lays out keys whose contents are [contents] long, null for a key that shows nothing,
 * on a strip [available] long. The keys keep their [normal] metrics when they fit on one
 * line. Otherwise they squeeze toward [tight], only as far as it takes to fit. When even
 * that is not enough they stay normal, and the ones that don't fit go on a second line,
 * or stay on the first with [singleLine], which then scrolls.
 */
internal fun planStrip(
    contents: List<Int?>,
    setApart: List<Boolean>,
    available: Int,
    singleLine: Boolean,
    normal: KeyMetrics,
    tight: KeyMetrics,
): StripPlan {
    fun plan(metrics: KeyMetrics, fits: Boolean): StripPlan {
        val sizes = contents.map { content -> content?.let(metrics::keySize) }
        val leads = setApart.map { apart -> if (apart) metrics.setApartGap.roundToInt() else 0 }
        val spacing = metrics.spacing.roundToInt()
        val extents = sizes.mapIndexed { index, size -> size?.plus(leads[index]) }
        val firstLine = if (fits || singleLine) sizes.size else firstRowKeyCount(extents, available, spacing)
        return StripPlan(sizes, leads, spacing, firstLine)
    }
    val squeeze = squeezeToFit(available) { fraction -> plan(normal.squeezedTo(tight, fraction), fits = true).length() }
    return plan(normal.squeezedTo(tight, squeeze ?: 0f), fits = squeeze != null)
}

/** How long every key of the plan is on one line. */
internal fun StripPlan.length(): Int {
    val extents = sizes.mapIndexedNotNull { index, size -> size?.plus(leads[index]) }
    return extents.sum() + spacing * (extents.size - 1).coerceAtLeast(0)
}

private const val SQUEEZE_SEARCH_STEPS = 10

/**
 * The least squeeze, from 0 (none) to 1 (full), at which keys take at most [available],
 * or null when they don't fit even fully squeezed. [length] gives their length at a
 * squeeze, and gets no longer as the squeeze grows.
 */
internal fun squeezeToFit(available: Int, length: (Float) -> Int): Float? {
    if (length(0f) <= available) return 0f
    if (length(1f) > available) return null
    var low = 0f
    var high = 1f
    repeat(SQUEEZE_SEARCH_STEPS) {
        val middle = (low + high) / 2
        if (length(middle) <= available) high = middle else low = middle
    }
    return high
}

/** How many of the keys with [widths] fit on a row [maxWidth] wide; at least one. */
internal fun firstRowKeyCount(widths: List<Int?>, maxWidth: Int, spacing: Int): Int {
    var used = 0
    var shown = 0
    widths.forEachIndexed { index, width ->
        if (width == null) return@forEachIndexed
        val next = if (shown == 0) width else used + spacing + width
        if (next > maxWidth && shown > 0) return index
        used = next
        shown++
    }
    return widths.size
}

/** The sizes of a strip's keys along it, and [crossMin], the least a key takes across it. */
private class StripDimens(
    val padding: Dp,
    val minSize: Dp,
    val spacing: Dp,
    val setApartGap: Dp,
    val crossMin: Dp,
) {
    fun metrics(density: Density): KeyMetrics = with(density) {
        KeyMetrics(padding.toPx(), minSize.toPx(), spacing.toPx(), setApartGap.toPx())
    }
}

private val KEY_HEIGHT = 30.dp

// A shared minimum width keeps one-glyph keys (arrows, icons) as wide as Esc or Tab.
private val KEY_WIDTH = 40.dp

// A row's keys squeeze in width; their height is a minimum, so they grow with the system font size.
private val ROW = StripDimens(padding = 10.dp, minSize = KEY_WIDTH, spacing = 6.dp, setApartGap = 8.dp, crossMin = KEY_HEIGHT)
private val TIGHT_ROW = StripDimens(padding = 4.dp, minSize = 28.dp, spacing = 3.dp, setApartGap = 4.dp, crossMin = KEY_HEIGHT)

// A column's keys squeeze in height, and are all as wide as the widest.
private val COLUMN = StripDimens(padding = 3.dp, minSize = KEY_HEIGHT, spacing = 6.dp, setApartGap = 8.dp, crossMin = KEY_WIDTH)
private val TIGHT_COLUMN = StripDimens(padding = 2.dp, minSize = 24.dp, spacing = 3.dp, setApartGap = 4.dp, crossMin = KEY_WIDTH)

private val ROW_KEY_PADDING = PaddingValues(vertical = 3.dp)
private val COLUMN_KEY_PADDING = PaddingValues(horizontal = 6.dp)

/**
 * The padding the content of a key in an [AccessoryKeyStrip] takes: none along the strip,
 * where the strip sizes the key itself.
 */
internal fun stripKeyPadding(vertical: Boolean): PaddingValues = if (vertical) COLUMN_KEY_PADDING else ROW_KEY_PADDING

/**
 * The accessory keys as a row, or as a column when [vertical]. Keys that don't fit on one
 * line get narrower, and closer together, until they do. When that is not enough they
 * keep their size: the ones that fit stay on the first line and the rest go on a second
 * one that scrolls, or with [singleLine] all stay on one line that scrolls, so a long
 * layout or a large font never hides a key. It is one layout, which measures every key
 * to size and split them, so each key is composed once; only a drag that starts on the
 * line that scrolls moves it.
 */
@Composable
internal fun AccessoryKeyStrip(
    keys: List<StripKey>,
    vertical: Boolean,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = remember { StripScroll() }
    val orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, orientation, false)
    Layout(
        contents = keys.map { it.content },
        modifier = modifier
            .clipToBounds()
            .scrollable(scroll.state, orientation, reverseDirection = reverse)
            .semantics {
                val range = ScrollAxisRange(value = { scroll.offset }, maxValue = { scroll.maxOffset })
                if (vertical) verticalScrollAxisRange = range else horizontalScrollAxisRange = range
            }
            // Sees each move before the scroll does: a drag that starts on the line that
            // stays put is taken here once it passes the touch slop, so it scrolls nothing.
            .pointerInput(scroll, vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val across = if (vertical) down.position.x else down.position.y
                    if (across >= scroll.start) return@awaitEachGesture
                    if (vertical) {
                        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (drag != null) verticalDrag(drag.id) { it.consume() }
                    } else {
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (drag != null) horizontalDrag(drag.id) { it.consume() }
                    }
                }
            },
    ) { measurables, constraints ->
        measureStrip(measurables, constraints, keys.map { it.setApart }, vertical, singleLine, scroll)
    }
}

/** How far the scrolling line of an [AccessoryKeyStrip] is scrolled. */
private class StripScroll {
    var offset by mutableFloatStateOf(0f)
        private set

    // Set by each measure: how far the line can scroll, and where across the strip it starts.
    var maxOffset = 0f
    var start = Int.MAX_VALUE

    val state = ScrollableState { delta ->
        val target = (offset + delta).coerceIn(0f, maxOffset)
        val consumed = target - offset
        offset = target
        consumed
    }
}

/** A key that shows something: what it draws, the room before it, and its size along the strip. */
private class StripSlot(val placeables: List<Placeable>, val lead: Int, val size: Int)

private fun MeasureScope.measureStrip(
    // Each key's content, empty for one that shows nothing, such as Files with no handler.
    keys: List<List<Measurable>>,
    constraints: Constraints,
    setApart: List<Boolean>,
    vertical: Boolean,
    singleLine: Boolean,
    scroll: StripScroll,
): MeasureResult {
    val dimens = if (vertical) COLUMN else ROW
    val bound = if (vertical) constraints.maxHeight else constraints.maxWidth
    val available = if (bound == Constraints.Infinity) Int.MAX_VALUE else bound
    val crossMin = dimens.crossMin.roundToPx()
    val columnWidth = max(crossMin, keys.flatten().maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0)
    val contents = keys.map { key ->
        key.maxOfOrNull { if (vertical) it.maxIntrinsicHeight(columnWidth) else it.maxIntrinsicWidth(Constraints.Infinity) }
    }
    val tight = if (vertical) TIGHT_COLUMN else TIGHT_ROW
    val plan = planStrip(contents, setApart, available, singleLine, dimens.metrics(this), tight.metrics(this))
    val slots = keys.mapIndexed { index, key ->
        val size = plan.sizes[index] ?: return@mapIndexed null
        val keyConstraints = if (vertical) {
            Constraints.fixed(columnWidth, size)
        } else {
            Constraints(minWidth = size, maxWidth = size, minHeight = crossMin)
        }
        StripSlot(key.map { it.measure(keyConstraints) }, plan.leads[index], size)
    }
    val first = slots.take(plan.firstLineCount).filterNotNull()
    val second = slots.drop(plan.firstLineCount).filterNotNull()
    val scrolling = if (singleLine) first else second
    val firstCross = lineCross(first, vertical)
    val secondCross = lineCross(second, vertical)
    val secondStart = firstCross + dimens.spacing.roundToPx()
    scroll.start = when {
        singleLine -> 0
        second.isEmpty() -> Int.MAX_VALUE
        else -> secondStart
    }
    scroll.maxOffset = (lineLength(scrolling, plan.spacing) - available).coerceAtLeast(0).toFloat()
    val along = if (bound == Constraints.Infinity) lineLength(first, plan.spacing) else bound
    val across = if (second.isEmpty()) firstCross else secondStart + secondCross
    val width = if (vertical) across.coerceAtLeast(constraints.minWidth) else along
    val height = if (vertical) along else across.coerceAtLeast(constraints.minHeight)
    return layout(width, height) {
        val offset = scroll.offset.coerceAtMost(scroll.maxOffset).roundToInt()
        placeLine(first, if (singleLine) -offset else 0, 0, firstCross, plan.spacing, vertical)
        placeLine(second, -offset, secondStart, secondCross, plan.spacing, vertical)
    }
}

private fun lineLength(line: List<StripSlot>, spacing: Int): Int =
    line.sumOf { it.lead + it.size } + spacing * (line.size - 1).coerceAtLeast(0)

/** How much a line takes across the strip: its tallest key in a row, its widest in a column. */
private fun lineCross(line: List<StripSlot>, vertical: Boolean): Int =
    line.maxOfOrNull { slot -> slot.placeables.maxOf { if (vertical) it.width else it.height } } ?: 0

/** Places [line] one key after the other from [along], each centered in the [cross] the line takes from [across]. */
private fun Placeable.PlacementScope.placeLine(
    line: List<StripSlot>,
    along: Int,
    across: Int,
    cross: Int,
    spacing: Int,
    vertical: Boolean,
) {
    var position = along
    line.forEach { slot ->
        position += slot.lead
        slot.placeables.forEach {
            if (vertical) {
                it.place(across + (cross - it.width) / 2, position)
            } else {
                it.placeRelative(position, across + (cross - it.height) / 2)
            }
        }
        position += slot.size + spacing
    }
}
