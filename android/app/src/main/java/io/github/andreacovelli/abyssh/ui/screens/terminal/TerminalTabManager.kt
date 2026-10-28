package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.components.AbyssStatusDot
import androidx.compose.material3.MaterialTheme
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.sessionLabel
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColorPalette
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

private data class TabRowState(
    val status: SessionStatus,
    val title: String?,
    val pwd: String?,
)

/**
 * Global expanded tab manager for strip mode.
 *
 * Full-screen overlay listing all active sessions with host/profile name,
 * status, optional terminal title, user@host, PWD.  Each row exposes
 * labeled duplicate and close actions. [multiplexerPanel] draws below the list.
 */
@Composable
fun TerminalTabManager(
    visible: Boolean,
    tabs: List<TabSession>,
    activeTabId: String?,
    focusedTabIndex: Int,
    onFocusedTabIndexChange: (Int) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDuplicateTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onDismiss: () -> Unit,
    multiplexerPanel: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = AbyssColors.current
    val maxIndex = (tabs.size - 1).coerceAtLeast(0)
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = visible) { onDismiss() }
    LaunchedEffect(tabs.size) { onFocusedTabIndexChange(focusedTabIndex.coerceIn(0, maxIndex)) }
    LaunchedEffect(activeTabId, tabs) {
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        if (activeIndex >= 0) onFocusedTabIndexChange(activeIndex)
    }
    FocusWithoutSoftKeyboard(visible, focusRequester)

    val closeDescription = stringResource(R.string.tab_manager_close)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .semantics { contentDescription = closeDescription }
                .clickable(onClick = onDismiss)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    handleTabManagerKey(
                        event = event,
                        tabCount = tabs.size,
                        focusedTabIndex = focusedTabIndex,
                        onFocusedTabIndexChange = onFocusedTabIndexChange,
                        onConfirm = {
                            tabs.getOrNull(focusedTabIndex)?.let { onSelectTab(it.id) }
                            onDismiss()
                        },
                        onDismiss = onDismiss,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(colors.surface)
                    .border(1.dp, colors.border.copy(alpha = 0.65f), MaterialTheme.shapes.large)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                TabManagerHeader(sessionCount = tabs.size)
                val addAndDismiss = {
                    onAddTab()
                    onDismiss()
                }
                if (tabs.isNotEmpty()) {
                    TabManagerList(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        focusedTabIndex = focusedTabIndex.coerceIn(0, maxIndex),
                        visible = visible,
                        onSelectTab = onSelectTab,
                        onCloseTab = onCloseTab,
                        onDuplicateTab = { id ->
                            onDuplicateTab(id)
                            onDismiss()
                        },
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    TabManagerFooter(onAddTab = addAndDismiss)
                } else {
                    TabManagerEmptyState(onAddTab = addAndDismiss)
                }
                multiplexerPanel()
            }
        }
    }
}

/** Arrow and page keys move the focus, Enter opens the focused tab, and Escape closes. */
private fun handleTabManagerKey(
    event: KeyEvent,
    tabCount: Int,
    focusedTabIndex: Int,
    onFocusedTabIndexChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    val keyUp = event.type == KeyEventType.KeyUp
    val step =
        when (event.key) {
            Key.DirectionUp, Key.PageUp -> -1
            Key.DirectionDown, Key.PageDown -> 1
            else -> 0
        }
    when {
        step != 0 -> if (keyUp && tabCount > 0) onFocusedTabIndexChange((focusedTabIndex + step).mod(tabCount))
        event.key == Key.Escape -> if (keyUp) onDismiss()
        event.key == Key.Enter -> if (keyUp) onConfirm()
        else -> return false
    }
    return true
}

/** Takes key focus for hardware navigation while keeping the soft keyboard hidden. */
@Composable
private fun FocusWithoutSoftKeyboard(visible: Boolean, focusRequester: FocusRequester) {
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        if (visible) {
            keyboardController?.hide()
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun TabManagerHeader(sessionCount: Int) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        AbyssText(
            stringResource(R.string.tab_manager_title),
            style = typography.body,
            color = colors.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
        AbyssText(
            pluralStringResource(R.plurals.tab_manager_session_count, sessionCount, sessionCount),
            style = typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun TabManagerList(
    tabs: List<TabSession>,
    activeTabId: String?,
    focusedTabIndex: Int,
    visible: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDuplicateTab: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusedTabIndex, tabs.size, visible) {
        if (visible) listState.animateScrollToItem(focusedTabIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.id }) { idx, tab ->
            TabManagerRow(
                tab = tab,
                label = tab.sessionLabel(tabs),
                isActive = tab.id == activeTabId,
                isFocused = idx == focusedTabIndex,
                onSelect = { onSelectTab(tab.id) },
                onDuplicate = { onDuplicateTab(tab.id) },
                onClose = { onCloseTab(tab.id) },
            )
        }
    }
}

@Composable
private fun TabManagerRow(
    tab: TabSession,
    label: String,
    isActive: Boolean,
    isFocused: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AbyssColors.current
    val rowState by remember(tab) {
        tab.sessionState
            .map { TabRowState(it.status, it.title?.takeIf(String::isNotBlank), it.pwd?.takeIf(String::isNotBlank)) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = TabRowState(SessionStatus.Disconnected, null, null))
    val rowDescription = stringResource(
        if (isActive) R.string.tab_manager_row_active else R.string.tab_manager_row_inactive,
        statusLabel(rowState.status),
        label,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (isFocused) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .semantics { contentDescription = rowDescription }
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusDotColor(rowState.status, colors)),
        )
        TabManagerRowText(
            label = label,
            endpoint = terminalTabEndpointLabel(tab),
            state = rowState,
            isActive = isActive,
            isFocused = isFocused,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabManagerRowAction(
                text = stringResource(R.string.tab_manager_dup),
                contentDescription = stringResource(R.string.tab_manager_duplicate, label),
                color = colors.textSecondary,
                onClick = onDuplicate,
            )
            TabManagerRowAction(
                text = stringResource(R.string.action_close),
                contentDescription = stringResource(R.string.tab_manager_close_tab, label),
                color = colors.textMuted,
                onClick = onClose,
            )
        }
    }
}

/** The terminal title (or the tab label), then the label when a title replaced it, then user@host and PWD. */
@Composable
private fun TabManagerRowText(
    label: String,
    endpoint: String,
    state: TabRowState,
    isActive: Boolean,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AbyssText(
                state.title ?: label,
                style = typography.body,
                color = if (isFocused) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                AbyssStatusDot(active = true, size = 8.dp)
            }
        }
        if (state.title != null) {
            AbyssText(label, style = typography.bodySmall, color = colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AbyssText(endpoint, style = typography.codeSmall, color = colors.textMuted)
            state.pwd?.let { AbyssText(it, style = typography.codeSmall, color = colors.textMuted) }
        }
    }
}

@Composable
private fun TabManagerRowAction(
    text: String,
    contentDescription: String,
    color: Color,
    onClick: () -> Unit,
) {
    AbyssButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        variant = AbyssButtonVariant.Ghost,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        contentDescription = contentDescription,
    ) {
        AbyssText(text, style = AbyssTypography.current.labelSmall, color = color)
    }
}

@Composable
private fun TabManagerFooter(onAddTab: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PaletteHint("\u21B5", stringResource(R.string.action_open))
        PaletteHint("esc", stringResource(R.string.action_dismiss))
        Spacer(modifier = Modifier.weight(1f))
        AbyssButton(
            onClick = onAddTab,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
            variant = AbyssButtonVariant.Outlined,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            AbyssText(
                stringResource(R.string.tab_manager_new),
                style = AbyssTypography.current.labelSmall,
                color = AbyssColors.current.accent,
            )
        }
    }
}

@Composable
private fun TabManagerEmptyState(onAddTab: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AbyssText(stringResource(R.string.terminal_no_sessions), style = typography.body, color = colors.textMuted)
            Spacer(modifier = Modifier.height(12.dp))
            AbyssButton(
                onClick = onAddTab,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                variant = AbyssButtonVariant.Outlined,
            ) {
                AbyssText(stringResource(R.string.terminal_new_connection), style = typography.label, color = colors.accent)
            }
        }
    }
}

/**
 * Status dot color matching the one used in [TerminalTabStrip].
 */
private fun statusDotColor(status: SessionStatus, colors: AbyssColorPalette): Color = when (status) {
    SessionStatus.Connected -> colors.success
    SessionStatus.Connecting,
    SessionStatus.Reconnecting -> colors.accent
    SessionStatus.Disconnected,
    SessionStatus.Error -> colors.error
}
