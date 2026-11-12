package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssBottomSheet
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssSheetHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinShortcutStore
import io.github.andreacovelli.abyssh.ui.terminal.ShortcutMenuLayout
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
internal fun AbysshCommandsEditorSheet(
    visible: Boolean,
    builtinShortcuts: Map<String, String>,
    customGroups: List<TerminalCustomKeyGroup>,
    menuLayout: ShortcutMenuLayout,
    onSave: (Map<String, String>, ShortcutMenuLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var draft by remember(builtinShortcuts, visible) { mutableStateOf(builtinShortcuts) }
    var draftLayout by remember(menuLayout, visible) { mutableStateOf(menuLayout) }
    val rows = shortcutMenuRows(draft, customGroups, draftLayout)

    AbyssBottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.88f) {
        AbyssSheetHeader(
            title = stringResource(R.string.settings_app_commands),
            subtitle = stringResource(R.string.settings_app_commands_sheet_hint),
            onClose = onDismiss,
        )

        // Weighted so the list scrolls and the buttons below stay on screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ShortcutMenuList(
                rows = rows,
                shortcuts = draft,
                layout = draftLayout,
                onLayoutChange = { draftLayout = it },
                onKeyChange = { command, key -> draft = draft.withShortcut(command, key) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = {
                    draft = BuiltinShortcutStore.defaults
                    draftLayout = ShortcutMenuLayout()
                },
                variant = AbyssButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_reset), style = typography.label)
            }

            AbyssButton(
                onClick = { onSave(draft, draftLayout.copy(order = rows.map { it.id })) },
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_save), style = typography.label, color = colors.onAccent)
            }
        }
    }
}

/**
 * The menu's items in order. A long press on a row's handle and a vertical drag moves
 * it; screen readers move rows with their move up and move down actions.
 */
@Composable
private fun ShortcutMenuList(
    rows: List<ShortcutMenuRow>,
    shortcuts: Map<String, String>,
    layout: ShortcutMenuLayout,
    onLayoutChange: (ShortcutMenuLayout) -> Unit,
    onKeyChange: (BuiltinCommand, String) -> Unit,
) {
    val reorderStepPx = with(LocalDensity.current) { 64.dp.toPx() }
    val dragState = remember(reorderStepPx) { DragReorderState<String>(reorderStepPx) }
    val currentRows by rememberUpdatedState(rows)
    val currentLayout by rememberUpdatedState(layout)
    val moveUpLabel = stringResource(R.string.action_move_up)
    val moveDownLabel = stringResource(R.string.action_move_down)

    fun move(id: String, direction: Int): Boolean {
        val moved = currentLayout.moving(currentRows, id, direction) ?: return false
        onLayoutChange(moved)
        return true
    }

    rows.forEachIndexed { index, row ->
        key(row.id) {
            val accessibilityActions = buildList {
                if (index > 0) add(accessibilityAction(moveUpLabel) { move(row.id, -1) })
                if (index < rows.lastIndex) add(accessibilityAction(moveDownLabel) { move(row.id, 1) })
            }
            ShortcutMenuRowItem(
                row = row,
                key = when (row) {
                    is ShortcutMenuRow.Builtin -> shortcuts[row.command.id] ?: ""
                    is ShortcutMenuRow.Custom -> row.key.toString()
                },
                shown = layout.isShown(row.id),
                dragging = dragState.draggingKey == row.id,
                dragOffsetPx = dragState.offsetFor(row.id),
                modifier = Modifier.semantics { customActions = accessibilityActions },
                handleModifier = Modifier.pointerInput(row.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragState.start(row.id) },
                        onDragEnd = dragState::stop,
                        onDragCancel = dragState::stop,
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.drag(dragAmount.y) { direction -> move(row.id, direction) }
                        },
                    )
                },
                onShownChange = { onLayoutChange(currentLayout.withShown(row.id, it)) },
                onKeyChange = { typed -> (row as? ShortcutMenuRow.Builtin)?.let { onKeyChange(it.command, typed) } },
            )
        }
    }
}

/**
 * One menu item: its drag handle, its name, its key, editable for a builtin command, and
 * whether the menu shows it.
 */
@Composable
private fun ShortcutMenuRowItem(
    row: ShortcutMenuRow,
    key: String,
    shown: Boolean,
    dragging: Boolean,
    dragOffsetPx: Float,
    onShownChange: (Boolean) -> Unit,
    onKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val label = when (row) {
        is ShortcutMenuRow.Builtin -> stringResource(row.command.label)
        is ShortcutMenuRow.Custom -> row.labels
    }
    val description = when (row) {
        is ShortcutMenuRow.Builtin -> stringResource(row.command.description)
        is ShortcutMenuRow.Custom -> stringResource(R.string.settings_menu_custom_action)
    }
    val showLabel = stringResource(R.string.settings_menu_show, label)
    Row(
        modifier = modifier
            .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
            .zIndex(if (dragging) 1f else 0f)
            .fillMaxWidth()
            .background(if (dragging) colors.accent.copy(alpha = 0.12f) else colors.surface)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = handleModifier.height(40.dp).width(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_drag_indicator),
                contentDescription = stringResource(R.string.action_reorder),
                tint = colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(label, style = typography.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AbyssText(description, style = typography.bodySmall, color = colors.textMuted)
        }
        when (row) {
            is ShortcutMenuRow.Builtin -> AbyssTextField(
                value = key,
                onValueChange = onKeyChange,
                label = label,
                showLabel = false,
                placeholder = stringResource(R.string.settings_shortcut_key_placeholder),
                singleLine = true,
                autoFocus = false,
                modifier = Modifier.width(64.dp),
            )
            // A custom action's key is set in its own form.
            is ShortcutMenuRow.Custom -> Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                AbyssText(key, style = typography.code)
            }
        }
        AbyssSwitch(
            checked = shown,
            onCheckedChange = onShownChange,
            modifier = Modifier.semantics { contentDescription = showLabel },
        )
    }
}

/**
 * Binds the last character of [typed] to [command], keeping each key bound to at most one
 * command. Keys are lowercased to match runtime key matching, so case variants conflict.
 */
internal fun Map<String, String>.withShortcut(command: BuiltinCommand, typed: String): Map<String, String> {
    val key = typed.takeLast(1).lowercase()
    val updated = toMutableMap()
    if (key.isNotEmpty()) {
        updated.entries
            .filter { it.value == key && it.key != command.id }
            .forEach { updated[it.key] = "" }
    }
    updated[command.id] = key
    return updated
}
