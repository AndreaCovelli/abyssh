package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionHandle
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionState
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Where the copy and paste menu goes: centered over the selection, above it when it
 * fits and below it otherwise, and always inside the canvas.
 */
internal fun selectionMenuOffset(state: TerminalSelectionState, menuSize: IntSize, gapPx: Float): IntOffset {
    val menuWidth = menuSize.width.coerceAtLeast(1)
    val menuHeight = menuSize.height.coerceAtLeast(1)
    val centerX = (state.boundsLeft + state.boundsRight) / 2f
    val maxX = (state.canvasWidthPx - menuWidth).coerceAtLeast(0)
    val x = (centerX - menuWidth / 2f).toInt().coerceIn(0, maxX)
    val aboveY = (state.boundsTop - menuHeight - gapPx).toInt()
    val belowY = (state.boundsBottom + gapPx).toInt()
    val maxY = (state.canvasHeightPx - menuHeight).coerceAtLeast(0)
    val y = if (aboveY >= 0) aboveY else belowY.coerceIn(0, maxY)
    return IntOffset(x, y)
}

/** The selection's drag handles and its copy and paste menu, drawn over the canvas. */
@Composable
internal fun TerminalSelectionOverlay(input: TerminalInputController) {
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val state = input.selectionState ?: return
    SelectionHandles(input, state)
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    SelectionMenu(
        showCopy = !state.text.isNullOrEmpty(),
        showPaste = input.hasClipboardText,
        onCopy = input::copySelection,
        onPaste = {
            input.pasteClipboard()
            input.requestFocus()
        },
        modifier = Modifier
            .offset { selectionMenuOffset(state, menuSize, gapPx) }
            .onGloballyPositioned { menuSize = it.size },
    )
}

@Composable
private fun SelectionHandles(input: TerminalInputController, state: TerminalSelectionState) {
    val colors = AbyssColors.current
    val selection = input.selection
    val anchorIsStart = selection != null && selection.anchorIndex <= selection.focusIndex
    TerminalSelectionHandle(
        tipX = state.startOffset.x,
        tipY = state.startOffset.y + state.cellHeightPx / 2f,
        color = colors.accent,
        borderColor = colors.background,
        cellWidthPx = state.cellWidthPx,
        cellHeightPx = state.cellHeightPx,
        cols = state.cols,
        startCellProvider = { input.selection?.let { minOf(it.anchorIndex, it.focusIndex) } ?: 0 },
        onDragToCell = { cell -> input.selection = input.selection?.withStart(cell, updateAnchor = anchorIsStart) },
    )
    TerminalSelectionHandle(
        tipX = state.endOffset.x,
        tipY = state.endOffset.y - state.cellHeightPx / 2f,
        color = colors.accent,
        borderColor = colors.background,
        cellWidthPx = state.cellWidthPx,
        cellHeightPx = state.cellHeightPx,
        cols = state.cols,
        startCellProvider = { input.selection?.let { maxOf(it.anchorIndex, it.focusIndex) } ?: 0 },
        onDragToCell = { cell -> input.selection = input.selection?.withEnd(cell, updateAnchor = !anchorIsStart) },
    )
}

@Composable
private fun SelectionMenu(
    showCopy: Boolean,
    showPaste: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .shadow(3.dp, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCopy) SelectionMenuButton(stringResource(R.string.action_copy), onCopy)
        if (showPaste) SelectionMenuButton(stringResource(R.string.action_paste), onPaste)
    }
}

@Composable
private fun SelectionMenuButton(label: String, onClick: () -> Unit) {
    val colors = AbyssColors.current
    AbyssButton(
        onClick = onClick,
        variant = AbyssButtonVariant.Ghost,
        borderColor = colors.textMuted,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        AbyssText(label, style = AbyssTypography.current.label, color = colors.textMuted)
    }
}
