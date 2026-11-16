package io.github.andreacovelli.abyssh.ui.terminal

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssKey
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlinx.coroutines.withTimeoutOrNull

private const val INITIAL_REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

// The keys of a group's popover keep their size: the popover scrolls instead.
private val POPOVER_KEY_PADDING = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
private val POPOVER_KEY_HEIGHT = 30.dp

/**
 * The terminal's accessory keys, as a row when [position] is the top or the bottom and as
 * a column when it is a side. A group's keys open next to the bar, on the terminal's side.
 */
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
    endSessionLabel: String? = null,
    useSingleRow: Boolean = false,
    position: AccessoryBarPosition = AccessoryBarPosition.Bottom,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
) {
    val vertical = position.isVertical
    val keyPadding = stripKeyPadding(vertical)
    // Shift lights up while Caps Lock is on, though it sends no Shift of its own.
    val shownModifiers =
        if (capsLockOn) modifierState.copy(shift = true, locked = modifierState.locked + TerminalModifier.Shift) else modifierState
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val expandedGroup = entries.firstNotNullOfOrNull { entry ->
        (entry as? ResolvedAccessoryEntry.Group)?.group?.takeIf { it.id == expandedGroupId }
    }

    val keys = buildList {
        add(StripKey { ShortcutsButton(onAbysshKey = onAbysshKey, active = abysshKeyActive, buttonPadding = keyPadding) })
        entries.forEach { entry ->
            add(
                StripKey {
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = shownModifiers,
                        onAction = onAction,
                        onToggleGroup = { id ->
                            expandedGroupId = if (expandedGroupId == id) null else id
                        },
                        buttonPadding = keyPadding,
                    )
                },
            )
        }
        add(StripKey { FilesButton(onOpenFiles = onOpenFiles, buttonPadding = keyPadding) })
        add(StripKey { ZoomButton(onZoom = onZoom, active = zoomActive, buttonPadding = keyPadding) })
        // Set apart from the other keys, so it is harder to hit by mistake.
        add(
            StripKey(setApart = true) {
                EndSessionButton(
                    onEndSession = onEndSession,
                    label = endSessionLabel ?: stringResource(R.string.terminal_disconnect),
                    buttonPadding = keyPadding,
                )
            },
        )
    }
    val strip: @Composable () -> Unit = {
        AccessoryKeyStrip(
            keys = keys,
            vertical = vertical,
            singleLine = useSingleRow,
            modifier = (if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()).padding(contentPadding),
        )
    }
    val popover: @Composable () -> Unit = {
        expandedGroup?.let { group ->
            if (vertical) {
                GroupPopoverColumn(group = group, modifierState = shownModifiers, onAction = onAction)
            } else {
                GroupPopover(group = group, modifierState = shownModifiers, onAction = onAction)
            }
        }
    }
    // Left and right are sides of the screen, whichever way the text runs.
    val stripAtRowStart = (position == AccessoryBarPosition.Left) == (LocalLayoutDirection.current == LayoutDirection.Ltr)
    when {
        vertical -> Row(modifier = modifier) {
            if (stripAtRowStart) strip()
            popover()
            if (!stripAtRowStart) strip()
        }
        else -> Column(modifier = modifier) {
            if (position == AccessoryBarPosition.Top) strip()
            popover()
            if (position != AccessoryBarPosition.Top) strip()
        }
    }
}

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
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
                .padding(horizontal = 8.dp, vertical = 4.dp)
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
                    buttonPadding = POPOVER_KEY_PADDING,
                    modifier = Modifier.heightIn(min = POPOVER_KEY_HEIGHT),
                )
            }
        }
    }
}

/** A group's keys beside a bar that runs down a side: a column of keys of one width. */
@Composable
private fun GroupPopoverColumn(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
) {
    val colors = AbyssColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(6.dp)
            .verticalScroll(rememberScrollState())
            .width(IntrinsicSize.Max),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        group.children.forEach { child ->
            AccessoryButton(
                item = child,
                modifierState = modifierState,
                onAction = onAction,
                buttonPadding = POPOVER_KEY_PADDING,
                modifier = Modifier.fillMaxWidth().heightIn(min = POPOVER_KEY_HEIGHT),
            )
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single ->
            AccessoryButton(
                item = entry.item,
                modifierState = modifierState,
                onAction = onAction,
                buttonPadding = buttonPadding,
                modifier = modifier,
            )
        is ResolvedAccessoryEntry.Group ->
            GroupButton(
                group = entry.group,
                expanded = expanded,
                onToggle = { onToggleGroup(entry.group.id) },
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AbyssKey(
        onClick = onToggle,
        active = expanded,
        modifier = modifier,
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
    if (specialKey != null && specialKey.isRepeatable) {
        RepeatableAccessoryButton(
            item = item,
            onAction = onAction,
            buttonPadding = buttonPadding,
            modifier = modifier,
        )
    } else {
        val locked = toggleModifier != null && modifierState.isLocked(toggleModifier)
        val armed = toggleModifier != null && modifierState.isEnabled(toggleModifier)
        val state = when {
            locked -> stringResource(R.string.accessory_modifier_locked)
            armed -> stringResource(R.string.accessory_modifier_next_key)
            else -> null
        }
        AbyssKey(
            onClick = { onAction(item.action) },
            active = locked,
            armed = armed,
            modifier = modifier
                .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier),
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onAbysshKey == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_bolt,
        contentDescription = stringResource(R.string.accessory_shortcuts),
        onClick = onAbysshKey,
        buttonPadding = buttonPadding,
        modifier = modifier,
        active = active,
    )
}

@Composable
private fun FilesButton(
    onOpenFiles: (() -> Unit)?,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onOpenFiles == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_folder,
        contentDescription = stringResource(R.string.accessory_open_files),
        onClick = onOpenFiles,
        buttonPadding = buttonPadding,
        modifier = modifier,
    )
}

/** Toggles zoom mode, which magnifies the terminal without changing its font size. */
@Composable
private fun ZoomButton(
    onZoom: (() -> Unit)?,
    active: Boolean,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onZoom == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_zoom_in,
        contentDescription = stringResource(R.string.accessory_zoom),
        onClick = onZoom,
        buttonPadding = buttonPadding,
        modifier = modifier,
        active = active,
    )
}

/** Ends the active session; back leaves it running instead. It comes last, set apart. */
@Composable
private fun EndSessionButton(
    onEndSession: (() -> Unit)?,
    label: String,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onEndSession == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_close,
        contentDescription = label,
        onClick = onEndSession,
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color = Color.Unspecified,
) {
    AbyssKey(
        onClick = onClick,
        active = active,
        modifier = modifier,
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
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val typography = AbyssTypography.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = modifier
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
            pressed = pressed,
            contentPadding = buttonPadding,
        ) {
            AbyssText(item.label, style = typography.label)
        }
    }
}
