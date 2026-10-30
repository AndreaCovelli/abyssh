package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.sessionLabel
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCanvas
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Full-screen tab switcher for the current host. [accessoryBar] draws the keyboard
 * accessory bar along the bottom edge and receives the modifier that places it;
 * [multiplexerPanel] draws below the tab list.
 */
@Composable
fun CommandPalette(
    tabs: List<TabSession>,
    activeTabId: String?,
    focusedTabIndex: Int,
    onFocusedTabIndexChange: (Int) -> Unit,
    onSearch: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onDismiss: () -> Unit,
    accessoryBar: @Composable (Modifier) -> Unit,
    multiplexerPanel: @Composable () -> Unit = {},
) {
    val colors = AbyssColors.current
    val entries = remember(tabs) { tabs.map { it to it.sessionLabel(tabs) } }
    val maxIndex = (entries.size - 1).coerceAtLeast(0)
    LaunchedEffect(entries.size) { onFocusedTabIndexChange(focusedTabIndex.coerceIn(0, maxIndex)) }
    LaunchedEffect(activeTabId, entries) {
        val activeIndex = entries.indexOfFirst { it.first.id == activeTabId }
        if (activeIndex >= 0) onFocusedTabIndexChange(activeIndex)
    }
    HideSoftKeyboardOnShow()

    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.background).clickable(onClick = onDismiss),
        ) {
            Column(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(colors.surface)
                        .border(1.dp, colors.border.copy(alpha = 0.65f), MaterialTheme.shapes.large),
            ) {
                PaletteHeader(shown = entries.size, total = tabs.size)
                if (entries.isNotEmpty()) {
                    PaletteTabList(
                        entries = entries,
                        activeTabId = activeTabId,
                        focusedTabIndex = focusedTabIndex.coerceIn(0, maxIndex),
                        canClose = tabs.size > 1,
                        onSelectTab = onSelectTab,
                        onCloseTab = onCloseTab,
                    )
                }
                entries.getOrNull(focusedTabIndex)?.first?.let { FocusedTabPreview(it) }
                PaletteDivider()
                PaletteFooter(onSearch = onSearch, onAddTab = onAddTab)
                multiplexerPanel()
            }
            accessoryBar(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(colors.background.copy(alpha = 0.92f)),
            )
        }
    }
}

/** The palette is driven from a hardware keyboard or by touch, so hide the soft keyboard. */
@Composable
private fun HideSoftKeyboardOnShow() {
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        keyboardController?.hide()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

@Composable
private fun PaletteHeader(shown: Int, total: Int) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        AbyssText(
            stringResource(R.string.tab_manager_title),
            style = typography.body,
            color = colors.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
        AbyssText(
            "$shown/$total",
            style = typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun PaletteDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AbyssColors.current.border))
}

@Composable
private fun PaletteTabList(
    entries: List<Pair<TabSession, String>>,
    activeTabId: String?,
    focusedTabIndex: Int,
    canClose: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusedTabIndex, entries.size) { listState.animateScrollToItem(focusedTabIndex) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries.size) { idx ->
            val (tab, alias) = entries[idx]
            PaletteTabRow(
                tab = tab,
                alias = alias,
                isActive = tab.id == activeTabId,
                isFocused = idx == focusedTabIndex,
                canClose = canClose,
                onSelect = { onSelectTab(tab.id) },
                onClose = { onCloseTab(tab.id) },
            )
        }
    }
}

@Composable
private fun PaletteTabRow(
    tab: TabSession,
    alias: String,
    isActive: Boolean,
    isFocused: Boolean,
    canClose: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AbyssColors.current
    val title by remember(tab) {
        tab.sessionState.map { it.title?.takeIf(String::isNotBlank) }
    }.collectAsStateWithLifecycle(initialValue = null)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(if (isFocused) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier.size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) colors.success else Color.Transparent),
        )
        // The session name first, so tabs to one host stay apart; the terminal title below it.
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(
                alias,
                style = AbyssTypography.current.body,
                color = if (isFocused) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            title?.takeIf { it != alias }?.let {
                AbyssText(
                    it,
                    style = AbyssTypography.current.codeSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canClose) {
            AbyssCloseButton(
                onClick = onClose,
                contentDescription = stringResource(R.string.tab_manager_close_tab, alias),
                color = colors.textMuted,
                iconSize = 18.dp,
            )
        }
    }
}

/** A small, non-interactive render of the focused tab's screen. */
@Composable
private fun FocusedTabPreview(tab: TabSession) {
    val colors = AbyssColors.current
    val snapshot by remember(tab) { tab.sessionState.map { it.snapshot } }
        .collectAsStateWithLifecycle(initialValue = null)
    val shown = snapshot ?: return
    PaletteDivider()
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(
            modifier =
                Modifier.fillMaxWidth(0.68f)
                    .height(104.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.background.copy(alpha = 0.86f))
                    .border(1.dp, colors.border.copy(alpha = 0.8f), MaterialTheme.shapes.small)
                    .align(Alignment.Center),
        ) {
            TerminalCanvas(
                snapshot = { shown },
                fontSizeSp = 10.5f,
                fitSnapshotToCanvas = true,
                enableGestures = false,
                cursorColor = Color.Transparent,
                selectionBackgroundColor = Color.Transparent,
                selectionForegroundColor = Color.Transparent,
                onTap = {},
                onPrimaryClick = { _, _, _ -> },
                onScroll = { _, _, _ -> },
                onFontSizeChange = {},
                onSelectionChanged = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PaletteFooter(onSearch: () -> Unit, onAddTab: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PaletteHint("↵", stringResource(R.string.action_open))
        PaletteHint("esc", stringResource(R.string.action_dismiss))
        Spacer(modifier = Modifier.weight(1f))
        AbyssButton(
            onClick = onSearch,
            variant = AbyssButtonVariant.Outlined,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            testTag = "palette_search",
        ) {
            AbyssText(stringResource(R.string.palette_search), style = typography.labelSmall, color = colors.accent)
        }
        AbyssButton(
            onClick = onAddTab,
            variant = AbyssButtonVariant.Outlined,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            AbyssText(stringResource(R.string.tab_manager_new), style = typography.labelSmall, color = colors.accent)
        }
    }
}
