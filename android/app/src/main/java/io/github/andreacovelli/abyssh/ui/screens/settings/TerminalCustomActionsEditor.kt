package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.andreacovelli.abyssh.ui.components.AbyssBottomSheet
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssSheetHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.CustomActionModifier
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomActionStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.terminal.decodeCustomActionValue
import io.github.andreacovelli.abyssh.ui.terminal.encodeCustomActionValue
import io.github.andreacovelli.abyssh.ui.terminal.modifierStateForCustomAction
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class CustomKeyValueDraft(
    val id: Long,
    val key: String,
    val value: String,
    val shortcut: String? = null,
)

/** The add or edit form below the action list. */
@Stable
private class CustomActionForm {
    var visible by mutableStateOf(false)
    var showModifiers by mutableStateOf(false)
    var modifiers by mutableStateOf(setOf<CustomActionModifier>())
    var editingIndex by mutableStateOf<Int?>(null)
    var key by mutableStateOf("")
    var value by mutableStateOf("")
    var shortcut by mutableStateOf("")

    val baseValue: String get() = value.trim().trimEnd('\n', '\r')

    fun beginAdd() {
        editingIndex = null
        visible = true
    }

    fun beginEdit(index: Int, item: CustomKeyValueDraft) {
        val decoded = decodeCustomActionValue(item.value)
        key = item.key
        modifiers = decoded.modifiers
        value = decoded.text
        shortcut = item.shortcut ?: ""
        showModifiers = true
        visible = true
        editingIndex = index
    }

    fun clear() {
        visible = false
        key = ""
        value = ""
        shortcut = ""
        modifiers = emptySet()
        showModifiers = false
        editingIndex = null
    }

    fun toggleModifier(modifier: CustomActionModifier) {
        modifiers = if (modifier in modifiers) modifiers - modifier else modifiers + modifier
    }

    /** The draft this form describes, or `null` while the key or the value is empty. */
    fun toDraft(id: Long): CustomKeyValueDraft? {
        val trimmedKey = key.trim()
        val encoded = encodeCustomActionValue(baseValue, modifiers)
        if (trimmedKey.isEmpty() || encoded.isEmpty()) return null
        return CustomKeyValueDraft(id = id, key = trimmedKey, value = encoded, shortcut = shortcut.trim().ifEmpty { null })
    }

    /** Keeps the edited row's index in step when a drag swaps it with its neighbour. */
    fun onItemMoved(from: Int, to: Int) {
        if (editingIndex == from) {
            editingIndex = to
        } else if (editingIndex == to) {
            editingIndex = from
        }
    }
}

@Composable
internal fun TerminalCustomActionsEditorSheet(
    visible: Boolean,
    initialGroups: List<TerminalCustomKeyGroup>,
    onSave: (List<TerminalCustomKeyGroup>) -> Unit,
    onDismiss: () -> Unit,
) {
    val typography = AbyssTypography.current
    var draftItems by remember(initialGroups) {
        mutableStateOf(groupsToDraftItems(TerminalCustomActionStore.normalize(initialGroups)))
    }
    val form = remember { CustomActionForm() }

    fun persist(items: List<CustomKeyValueDraft>) {
        draftItems = items
        onSave(TerminalCustomActionStore.normalize(draftItemsToGroups(items)))
    }

    fun submitForm() {
        val nextId = (draftItems.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = form.toDraft(nextId) ?: return
        val index = form.editingIndex
        persist(
            if (index != null && index in draftItems.indices) {
                draftItems.toMutableList().also { it[index] = item.copy(id = it[index].id) }
            } else {
                draftItems + item
            },
        )
        form.clear()
    }

    AbyssBottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.88f, verticalSpacing = 12.dp) {
        AbyssSheetHeader(title = stringResource(R.string.settings_custom_actions), onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CustomActionList(
                items = draftItems,
                onItemsReordered = ::persist,
                onReorder = { items, from, to ->
                    draftItems = items
                    form.onItemMoved(from, to)
                },
                onEdit = { index, item ->
                    if (form.editingIndex == index) form.clear() else form.beginEdit(index, item)
                },
                onDelete = { index ->
                    persist(draftItems.filterIndexed { rowIndex, _ -> rowIndex != index })
                    if (form.editingIndex == index) form.clear()
                },
            )

            if (form.visible) {
                CustomActionFormCard(form = form, onSubmit = ::submitForm, onCancel = form::clear)
            } else {
                AbyssButton(
                    onClick = form::beginAdd,
                    variant = AbyssButtonVariant.Outlined,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    AbyssText(stringResource(R.string.custom_actions_add), style = typography.label)
                }
            }
        }
    }
}

/**
 * The saved actions. A long press on a row's handle drags it to a new position; the new
 * order is saved through [onItemsReordered] when the drag ends.
 */
@Composable
private fun CustomActionList(
    items: List<CustomKeyValueDraft>,
    onItemsReordered: (List<CustomKeyValueDraft>) -> Unit,
    onReorder: (items: List<CustomKeyValueDraft>, from: Int, to: Int) -> Unit,
    onEdit: (Int, CustomKeyValueDraft) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val reorderStepPx = with(LocalDensity.current) { 64.dp.toPx() }
    val dragState = remember(reorderStepPx) { DragReorderState<Long>(reorderStepPx) }
    val currentItems by rememberUpdatedState(items)
    var reorderDirty by remember { mutableStateOf(false) }

    items.forEachIndexed { index, item ->
        key(item.id) {
            CustomActionSwipeRow(
                keyText = item.key,
                valueText = item.value,
                shortcut = item.shortcut,
                dragging = dragState.draggingKey == item.id,
                dragOffsetPx = dragState.offsetFor(item.id),
                onEdit = { onEdit(index, item) },
                onDelete = { onDelete(index) },
                onReorderStart = {
                    dragState.start(item.id)
                    reorderDirty = false
                },
                onReorderDrag = { deltaY ->
                    dragState.drag(deltaY) { direction ->
                        val from = currentItems.indexOfFirst { it.id == item.id }
                        val updated = currentItems.moved(from, from + direction) ?: return@drag false
                        onReorder(updated, from, from + direction)
                        reorderDirty = true
                        true
                    }
                },
                onReorderEnd = {
                    if (reorderDirty) onItemsReordered(currentItems)
                    dragState.stop()
                    reorderDirty = false
                },
            )
        }
    }
}

@Composable
private fun CustomActionFormCard(
    form: CustomActionForm,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val valuePreview = readableActionPreview(form.baseValue, form.modifiers)

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssTextField(
                value = form.key,
                onValueChange = { form.key = it },
                label = stringResource(R.string.custom_actions_key_label),
                placeholder = "a",
                singleLine = true,
                autoFocus = false,
            )
            AbyssTextField(
                value = form.shortcut,
                onValueChange = { form.shortcut = it.takeLast(1) },
                label = stringResource(R.string.custom_actions_shortcut_label),
                placeholder = stringResource(R.string.custom_actions_shortcut_placeholder),
                singleLine = true,
                autoFocus = false,
            )
            ModifierPicker(form = form)
            AbyssTextField(
                value = form.value,
                onValueChange = { updated ->
                    form.value = updated
                    // Enter on the keyboard submits the action.
                    if (updated.contains('\n') || updated.contains('\r')) onSubmit()
                },
                label = stringResource(R.string.custom_actions_value_label),
                monospace = true,
                placeholder = ":q",
                singleLine = false,
                autoFocus = false,
                modifier = Modifier.heightIn(min = 72.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AbyssText(
                    text = if (valuePreview.isBlank()) {
                        stringResource(R.string.custom_actions_preview_empty)
                    } else {
                        stringResource(R.string.custom_actions_preview, valuePreview)
                    },
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AbyssButton(
                    onClick = onSubmit,
                    variant = AbyssButtonVariant.Outlined,
                    modifier = Modifier.weight(1f),
                ) {
                    val label = if (form.editingIndex != null) R.string.action_save else R.string.action_add
                    AbyssText(stringResource(label), style = typography.label)
                }
                AbyssButton(
                    onClick = onCancel,
                    variant = AbyssButtonVariant.Ghost,
                    borderColor = colors.textMuted,
                    modifier = Modifier.weight(1f),
                ) {
                    AbyssText(stringResource(R.string.action_cancel), style = typography.label, color = colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ModifierPicker(form: CustomActionForm) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssButton(
        onClick = { form.showModifiers = !form.showModifiers },
        variant = AbyssButtonVariant.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val enabledLabel = if (form.modifiers.isEmpty()) {
            stringResource(R.string.custom_actions_no_modifiers)
        } else {
            CustomActionModifier.entries
                .filter { it in form.modifiers }
                .joinToString(separator = "+") { it.label }
        }
        AbyssText(stringResource(R.string.custom_actions_modifiers, enabledLabel), style = typography.label)
    }

    AnimatedVisibility(visible = form.showModifiers) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CustomActionModifier.entries.forEach { modifier ->
                val enabled = modifier in form.modifiers
                AbyssButton(
                    onClick = { form.toggleModifier(modifier) },
                    variant = if (enabled) AbyssButtonVariant.Filled else AbyssButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    AbyssText(
                        modifier.label,
                        style = typography.labelSmall,
                        color = if (enabled) colors.onAccent else colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomActionSwipeRow(
    keyText: String,
    valueText: String,
    shortcut: String?,
    dragging: Boolean,
    dragOffsetPx: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReorderStart: () -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderEnd: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 120.dp.toPx() }
    val deleteThresholdPx = with(density) { 72.dp.toPx() }
    val offsetX = remember(keyText, valueText) { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.error.copy(alpha = 0.78f))
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            AbyssText(stringResource(R.string.custom_actions_swipe_delete), style = typography.label, color = colors.background)
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), dragOffsetPx.roundToInt()) }
                .fillMaxSize()
                .background(if (dragging) colors.accent.copy(alpha = 0.12f) else colors.surface)
                .padding(horizontal = 12.dp)
                .clickable(onClick = onEdit)
                .pointerInput(keyText, valueText) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (offsetX.value <= -deleteThresholdPx) {
                                onDelete()
                            } else {
                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                        },
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(40.dp)
                    .width(28.dp)
                    .pointerInput(keyText, valueText) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onReorderStart() },
                            onDragEnd = { onReorderEnd() },
                            onDragCancel = { onReorderEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onReorderDrag(dragAmount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_drag_indicator),
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            AbyssText(
                text = if (shortcut != null) "$keyText [$shortcut]" else keyText,
                style = typography.label,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                AbyssText(
                    text = readableStoredActionPreview(valueText),
                    style = typography.code,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun readableActionPreview(baseValue: String, modifiers: Set<CustomActionModifier>): String {
    if (baseValue.isEmpty()) {
        if (CustomActionModifier.Enter in modifiers) return "⏎"
        return ""
    }
    val transformed = modifierStateForCustomAction(modifiers - CustomActionModifier.Cmd).applyToText(baseValue)
    val prefix = if (CustomActionModifier.Cmd in modifiers) "Cmd+" else ""
    val suffix = if (CustomActionModifier.Enter in modifiers) "⏎" else ""
    return prefix + readablePreview(transformed) + suffix
}

private fun readableStoredActionPreview(stored: String): String {
    val decoded = decodeCustomActionValue(stored)
    return readableActionPreview(decoded.text, decoded.modifiers)
}

private fun readablePreview(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\u001b' -> append("Esc+")
                '\n' -> append('⏎')
                in '\u0000'..'\u001f' -> append("^${(ch.code + 64).toChar()}")
                else -> append(ch)
            }
        }
    }

private fun groupsToDraftItems(groups: List<TerminalCustomKeyGroup>): List<CustomKeyValueDraft> {
    var nextId = 1L
    return groups.flatMap { group ->
        group.actions.map { action ->
            CustomKeyValueDraft(
                id = nextId++,
                key = group.keyLabel,
                value = action.payload,
                shortcut = action.shortcut,
            )
        }
    }
}

private fun draftItemsToGroups(items: List<CustomKeyValueDraft>): List<TerminalCustomKeyGroup> {
    val grouped = linkedMapOf<String, MutableList<TerminalCustomAction>>()
    items.forEach { item ->
        val key = item.key.trim()
        val value = item.value
        if (key.isEmpty() || value.isEmpty()) return@forEach
        val actions = grouped.getOrPut(key) { mutableListOf() }
        actions += TerminalCustomAction(label = key, payload = value, shortcut = item.shortcut)
    }
    return grouped.map { (key, actions) -> TerminalCustomKeyGroup(keyLabel = key, actions = actions) }
}
