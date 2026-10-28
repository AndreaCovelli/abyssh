package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssBottomSheet
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssSheetHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.terminal.ResolvedAccessoryEntry
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlin.math.roundToInt

@Composable
internal fun AccessoryLayoutEditorSheet(
    visible: Boolean,
    selectedIds: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val allEntries = remember { TerminalAccessoryLayoutStore.allCatalogEntries() }
    var draftSelectedIds by remember(selectedIds) {
        mutableStateOf(TerminalAccessoryLayoutStore.normalizeIds(selectedIds))
    }

    AbyssBottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.82f) {
        AbyssSheetHeader(
            title = stringResource(R.string.settings_accessory_keys),
            subtitle = stringResource(R.string.settings_accessory_sheet_hint),
            onClose = onDismiss,
        )

        AccessoryPreviewCard(
            selectedIds = draftSelectedIds,
            onMove = { id, direction ->
                val index = draftSelectedIds.indexOf(id)
                val updated = draftSelectedIds.moved(index, index + direction)
                if (updated != null) draftSelectedIds = updated
                updated != null
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            allEntries.forEach { item ->
                val selected = item.id in draftSelectedIds
                AccessoryChooserRow(
                    entry = item,
                    selected = selected,
                    onClick = {
                        draftSelectedIds = if (selected) draftSelectedIds - item.id else draftSelectedIds + item.id
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = { draftSelectedIds = TerminalAccessoryLayoutStore.defaultLayoutIds() },
                variant = AbyssButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_reset), style = typography.label)
            }

            AbyssButton(
                onClick = { onSave(draftSelectedIds) },
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_save), style = typography.label, color = colors.onAccent)
            }
        }
    }
}

/** The selected keys in bar order. A long press and horizontal drag reorders them. */
@Composable
private fun AccessoryPreviewCard(
    selectedIds: List<String>,
    onMove: (id: String, direction: Int) -> Boolean,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val reorderStepPx = with(LocalDensity.current) { 40.dp.toPx() }
    val dragState = remember(reorderStepPx) { DragReorderState<String>(reorderStepPx) }
    val currentOnMove by rememberUpdatedState(onMove)
    val selectedEntries = remember(selectedIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(selectedIds)
    }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(stringResource(R.string.settings_accessory_preview), style = typography.label)
                AbyssText(
                    selectedEntries.size.toString(),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            if (selectedEntries.isEmpty()) {
                AbyssText(
                    stringResource(R.string.settings_no_accessory_keys),
                    style = typography.body,
                    color = colors.textMuted,
                )
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selectedEntries.forEach { item ->
                    key(item.id) {
                        PreviewKeyChip(
                            entry = item,
                            dragging = dragState.draggingKey == item.id,
                            dragOffsetPx = dragState.offsetFor(item.id),
                            modifier = Modifier.pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragState.start(item.id) },
                                    onDragEnd = dragState::stop,
                                    onDragCancel = dragState::stop,
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragState.drag(dragAmount.x) { direction -> currentOnMove(item.id, direction) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            AbyssText(
                stringResource(R.string.settings_accessory_drag_hint),
                style = typography.body,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun PreviewKeyChip(
    entry: ResolvedAccessoryEntry,
    dragging: Boolean,
    dragOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    Box(
        modifier = modifier
            .offset { IntOffset(x = dragOffsetPx.roundToInt(), y = 0) }
            .background(if (dragging) colors.accent.copy(alpha = 0.2f) else colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbyssText(
                text = entry.label,
                style = typography.label,
                color = if (dragging) colors.accent else colors.textPrimary,
            )
            if (entry is ResolvedAccessoryEntry.Group) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = if (dragging) colors.accent else colors.textMuted,
                    modifier = Modifier.padding(start = 2.dp).size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AccessoryChooserRow(
    entry: ResolvedAccessoryEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val group = entry as? ResolvedAccessoryEntry.Group
    val childCount = group?.group?.children?.size ?: 0
    val description =
        when {
            group == null -> stringResource(R.string.settings_accessory_key)
            childCount > 0 -> pluralStringResource(R.plurals.settings_accessory_group_count, childCount, childCount)
            else -> stringResource(R.string.settings_accessory_group)
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surface else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (selected) R.drawable.ic_check else R.drawable.ic_add),
                contentDescription = null,
                tint = if (selected) colors.accent else colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AbyssText(
                text = entry.label,
                style = typography.label,
                color = colors.textPrimary,
            )
            AbyssText(
                text = description,
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
