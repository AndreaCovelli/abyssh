package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.foundation.layout.widthIn
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssKey
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlinx.coroutines.withTimeoutOrNull

private const val INITIAL_REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

// Extra space before the end-session key, on top of the row's spacing, so it is harder to
// hit by mistake.
private val END_SESSION_GAP = 8.dp

@Composable
fun KeyboardAccessoryBar(
    entries: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    modifier: Modifier = Modifier,
    capsLockOn: Boolean = false,
    onZoom: (() -> Unit)? = null,
    zoomActive: Boolean = false,
    onAbysshKey: (() -> Unit)? = null,
    abysshKeyActive: Boolean = false,
    onOpenFiles: (() -> Unit)? = null,
    onEndSession: (() -> Unit)? = null,
    useSingleRow: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 6.dp,
) {
    // A minimum, so keys grow with the system font size.
    val buttonHeight = 30.dp
    val buttonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
    // A shared minimum width keeps one-glyph keys (arrows, icons) as wide as Esc or Tab.
    val keyModifier = Modifier.widthIn(min = 40.dp)
    // Shift lights up while Caps Lock is on, though it sends no Shift of its own.
    val shownModifiers = if (capsLockOn) modifierState.copy(shift = true) else modifierState
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val onChildAction: (AccessoryAction) -> Unit = { action ->
        onAction(action)
    }

    Column(modifier = modifier) {
        expandedGroupId?.let { groupId ->
            val expandedGroup =
                entries.firstOrNull { it is ResolvedAccessoryEntry.Group && it.group.id == groupId }
                    as? ResolvedAccessoryEntry.Group
            if (expandedGroup != null) {
                GroupPopover(
                    group = expandedGroup.group,
                    modifierState = shownModifiers,
                    onAction = onChildAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    horizontalPadding = horizontalPadding,
                )
            }
        }

        val keys: List<@Composable () -> Unit> = buildList {
            add {
                ShortcutsButton(
                    onAbysshKey = onAbysshKey,
                    active = abysshKeyActive,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
            }
            entries.forEach { entry ->
                add {
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = shownModifiers,
                        onAction = onAction,
                        onToggleGroup = { id ->
                            expandedGroupId = if (expandedGroupId == id) null else id
                        },
                        buttonHeight = buttonHeight,
                        buttonPadding = buttonPadding,
                        modifier = keyModifier,
                    )
                }
            }
            add {
                FilesButton(
                    onOpenFiles = onOpenFiles,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
            }
            add {
                ZoomButton(
                    onZoom = onZoom,
                    active = zoomActive,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
            }
            add {
                EndSessionButton(
                    onEndSession = onEndSession,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = Modifier.padding(start = END_SESSION_GAP).then(keyModifier),
                )
            }
        }
        val rowModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        if (useSingleRow) {
            ScrollingKeyRow(keys, modifier = rowModifier)
        } else {
            TwoKeyRows(keys, modifier = rowModifier)
        }
    }
}

private val KEY_SPACING = 6.dp

@Composable
private fun ScrollingKeyRow(keys: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KEY_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { it() }
    }
}

/**
 * The keys that fit on the first row, and the rest on a second row that scrolls sideways,
 * so a long layout or a large font never hides a key. Both rows are one layout, which
 * measures every key to split them, so each key is composed once; only a drag that
 * starts on the second row scrolls it.
 */
@Composable
private fun TwoKeyRows(keys: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    val scroll = remember { SecondRowScroll() }
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, false)
    Layout(
        contents = keys,
        modifier = modifier
            .clipToBounds()
            .scrollable(scroll.state, Orientation.Horizontal, reverseDirection = reverse)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(value = { scroll.offset }, maxValue = { scroll.maxOffset })
            }
            // Sees each move before the scroll does: a drag that starts on the first row is
            // taken here once it passes the touch slop, so it scrolls nothing.
            .pointerInput(scroll) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.y >= scroll.top) return@awaitEachGesture
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) horizontalDrag(drag.id) { it.consume() }
                }
            },
    ) { measurables, constraints ->
        // Each key's placeables, empty for one that shows nothing, such as Files with no handler.
        val placeables = measurables.map { key -> key.map { it.measure(Constraints()) } }
        val widths = placeables.map { key -> key.maxOfOrNull { it.width } }
        val spacing = KEY_SPACING.roundToPx()
        val split = firstRowKeyCount(widths, constraints.maxWidth, spacing)
        val first = placeables.take(split).filter { it.isNotEmpty() }
        val second = placeables.drop(split).filter { it.isNotEmpty() }
        val firstHeight = rowHeight(first)
        val width = constraints.maxWidth
        scroll.top = if (second.isEmpty()) Int.MAX_VALUE else firstHeight + spacing
        scroll.maxOffset = (rowWidth(second, spacing) - width).coerceAtLeast(0).toFloat()
        val height = if (second.isEmpty()) firstHeight else scroll.top + rowHeight(second)
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            placeRow(first, 0, 0, firstHeight, spacing)
            val offset = scroll.offset.coerceAtMost(scroll.maxOffset).roundToInt()
            placeRow(second, -offset, scroll.top, rowHeight(second), spacing)
        }
    }
}

/** How far the second row of [TwoKeyRows] is scrolled. */
private class SecondRowScroll {
    var offset by mutableFloatStateOf(0f)
        private set

    // Set by each measure: how far the row can scroll, and where it starts.
    var maxOffset = 0f
    var top = Int.MAX_VALUE

    val state = ScrollableState { delta ->
        val target = (offset + delta).coerceIn(0f, maxOffset)
        val consumed = target - offset
        offset = target
        consumed
    }
}

private fun rowWidth(keys: List<List<Placeable>>, spacing: Int): Int =
    keys.sumOf { key -> key.maxOf { it.width } } + spacing * (keys.size - 1).coerceAtLeast(0)

private fun rowHeight(keys: List<List<Placeable>>): Int = keys.maxOfOrNull { key -> key.maxOf { it.height } } ?: 0

/** Places [keys] side by side from ([x], [y]), each centered in a row [height] tall. */
private fun Placeable.PlacementScope.placeRow(keys: List<List<Placeable>>, x: Int, y: Int, height: Int, spacing: Int) {
    var left = x
    keys.forEach { key ->
        key.forEach { it.placeRelative(left, y + (height - it.height) / 2) }
        left += key.maxOf { it.width } + spacing
    }
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

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    horizontalPadding: Dp,
) {
    val colors = AbyssColors.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.children.forEach { child ->
                AccessoryButton(
                    item = child,
                    modifierState = modifierState,
                    onAction = onAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
        }
    }
}

@Composable
private fun AccessoryEntry(
    entry: ResolvedAccessoryEntry,
    expanded: Boolean,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onToggleGroup: (String) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single ->
            AccessoryButton(
                item = entry.item,
                modifierState = modifierState,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
                modifier = modifier,
            )
        is ResolvedAccessoryEntry.Group ->
            GroupButton(
                group = entry.group,
                expanded = expanded,
                onToggle = { onToggleGroup(entry.group.id) },
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
                modifier = modifier,
            )
    }
}

@Composable
private fun GroupButton(
    group: AccessoryKeyGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AbyssKey(
        onClick = onToggle,
        active = expanded,
        modifier = modifier.heightIn(min = buttonHeight),
        contentPadding = buttonPadding,
    ) {
        AbyssText(group.label, style = AbyssTypography.current.label)
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
    if (specialKey != null && specialKey.isRepeatable) {
        RepeatableAccessoryButton(
            item = item,
            onAction = onAction,
            buttonHeight = buttonHeight,
            buttonPadding = buttonPadding,
            modifier = modifier,
        )
    } else {
        AbyssKey(
            onClick = { onAction(item.action) },
            active = toggleModifier != null && modifierState.isEnabled(toggleModifier),
            modifier = modifier.heightIn(min = buttonHeight),
            contentPadding = buttonPadding,
        ) {
            AbyssText(item.label, style = AbyssTypography.current.label)
        }
    }
}

/**
 * Starts the shortcut prefix: the next key runs the Abyssh command bound to it. It comes
 * first so it stays in view when the single-row bar scrolls.
 */
@Composable
private fun ShortcutsButton(
    onAbysshKey: (() -> Unit)?,
    active: Boolean,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onAbysshKey == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_bolt,
        contentDescription = stringResource(R.string.accessory_shortcuts),
        onClick = onAbysshKey,
        buttonHeight = buttonHeight,
        buttonPadding = buttonPadding,
        modifier = modifier,
        active = active,
    )
}

@Composable
private fun FilesButton(
    onOpenFiles: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onOpenFiles == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_folder,
        contentDescription = stringResource(R.string.accessory_open_files),
        onClick = onOpenFiles,
        buttonHeight = buttonHeight,
        buttonPadding = buttonPadding,
        modifier = modifier,
    )
}

/** Toggles zoom mode, which magnifies the terminal without changing its font size. */
@Composable
private fun ZoomButton(
    onZoom: (() -> Unit)?,
    active: Boolean,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onZoom == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_zoom_in,
        contentDescription = stringResource(R.string.accessory_zoom),
        onClick = onZoom,
        buttonHeight = buttonHeight,
        buttonPadding = buttonPadding,
        modifier = modifier,
        active = active,
    )
}

/** Ends the active session; back leaves it running instead. It comes last, set apart. */
@Composable
private fun EndSessionButton(
    onEndSession: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onEndSession == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_close,
        contentDescription = stringResource(R.string.terminal_end_session),
        onClick = onEndSession,
        buttonHeight = buttonHeight,
        buttonPadding = buttonPadding,
        modifier = modifier,
        tint = AbyssColors.current.warning,
    )
}

@Composable
private fun AccessoryIconKey(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color = Color.Unspecified,
) {
    AbyssKey(
        onClick = onClick,
        active = active,
        modifier = modifier.heightIn(min = buttonHeight),
        contentPadding = buttonPadding,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = tint.takeOrElse { LocalContentColor.current },
        )
    }
}

@Composable
private fun RepeatableAccessoryButton(
    item: AccessoryKeyItem,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val typography = AbyssTypography.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = modifier
            .heightIn(min = buttonHeight)
            .pointerInput(item.action) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true

                    try {
                        // The key goes out when the finger lifts, or when the repeat delay
                        // runs out, so a swipe that scrolls the bar presses nothing. Null
                        // when the delay ran out; false when the scroll took the touch.
                        val released = withTimeoutOrNull(INITIAL_REPEAT_DELAY_MS) {
                            waitForUpOrCancellation() != null
                        }

                        if (released == true) {
                            currentOnAction(item.action)
                        } else if (released == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            while (true) {
                                currentOnAction(item.action)
                                val ended = withTimeoutOrNull(REPEAT_INTERVAL_MS) {
                                    waitForUpOrCancellation()
                                    true
                                } == true
                                if (ended) break
                            }
                        }
                    } finally {
                        pressed = false
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = item.label
                onClick(label = item.label) {
                    currentOnAction(item.action)
                    true
                }
            },
        propagateMinConstraints = true,
    ) {
        AbyssKey(
            modifier = Modifier.heightIn(min = buttonHeight),
            pressed = pressed,
            contentPadding = buttonPadding,
        ) {
            AbyssText(item.label, style = typography.label)
        }
    }
}
