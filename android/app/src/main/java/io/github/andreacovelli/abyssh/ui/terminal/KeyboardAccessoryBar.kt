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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAccessoryBar(
    entries: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    modifier: Modifier = Modifier,
    capsLockOn: Boolean = false,
    onSettings: (() -> Unit)? = null,
    onAbysshKey: (() -> Unit)? = null,
    abysshKeyActive: Boolean = false,
    onOpenFiles: (() -> Unit)? = null,
    onEndSession: (() -> Unit)? = null,
    useSingleRow: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 6.dp,
) {
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

        if (useSingleRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
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
                FilesButton(
                    onAbysshKey = onAbysshKey,
                    abysshKeyActive = abysshKeyActive,
                    onOpenFiles = onOpenFiles,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
                SettingsButton(
                    onSettings = onSettings,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
                EndSessionButton(
                    onEndSession = onEndSession,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    modifier = keyModifier,
                )
            }
            return
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxLines = 2,
        ) {
            entries.forEach { entry ->
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
            FilesButton(
                onAbysshKey = onAbysshKey,
                abysshKeyActive = abysshKeyActive,
                onOpenFiles = onOpenFiles,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
                modifier = keyModifier,
            )
            SettingsButton(
                onSettings = onSettings,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
                modifier = keyModifier,
            )
            EndSessionButton(
                onEndSession = onEndSession,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
                modifier = keyModifier,
            )
        }
    }
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
        modifier = modifier.height(buttonHeight),
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
            modifier = modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            AbyssText(item.label, style = AbyssTypography.current.label)
        }
    }
}

@Composable
private fun FilesButton(
    onAbysshKey: (() -> Unit)?,
    abysshKeyActive: Boolean,
    onOpenFiles: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onAbysshKey != null) {
        AbyssKey(
            onClick = onAbysshKey,
            active = abysshKeyActive,
            modifier = modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            AbyssText("⌘", style = AbyssTypography.current.label)
        }
    }
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

@Composable
private fun SettingsButton(
    onSettings: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (onSettings == null) return
    AccessoryIconKey(
        icon = R.drawable.ic_settings,
        contentDescription = stringResource(R.string.accessory_settings),
        onClick = onSettings,
        buttonHeight = buttonHeight,
        buttonPadding = buttonPadding,
        modifier = modifier,
    )
}

/** Ends the active session; back leaves it running instead. */
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
    tint: Color = Color.Unspecified,
) {
    AbyssKey(
        onClick = onClick,
        modifier = modifier.height(buttonHeight),
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
            .height(buttonHeight)
            .pointerInput(item.action) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    currentOnAction(item.action)

                    try {
                        val releasedDuringDelay = withTimeoutOrNull(INITIAL_REPEAT_DELAY_MS) {
                            waitForUpOrCancellation()
                        }

                        if (releasedDuringDelay == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            while (true) {
                                currentOnAction(item.action)
                                val released = withTimeoutOrNull(REPEAT_INTERVAL_MS) {
                                    waitForUpOrCancellation()
                                }
                                if (released != null) break
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
            modifier = Modifier.height(buttonHeight),
            pressed = pressed,
            contentPadding = buttonPadding,
        ) {
            AbyssText(item.label, style = typography.label)
        }
    }
}
