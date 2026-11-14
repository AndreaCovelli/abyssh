package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.SessionState
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import io.github.andreacovelli.abyssh.ui.HoldEscapeKey
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.terminal.KeyboardAccessoryBar
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCanvas
import io.github.andreacovelli.abyssh.ui.terminal.TerminalGridProbe
import io.github.andreacovelli.abyssh.ui.terminal.terminalImeMode
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import io.github.andreacovelli.abyssh.ui.theme.GhosttyTheme
import io.github.andreacovelli.abyssh.ui.theme.toRgbIntArray
import io.github.andreacovelli.abyssh.ui.theme.toTerminalPaletteBytes
import kotlin.math.roundToInt

// How faint the accessory bar is while its session opens, as a disabled control is.
private const val WAITING_FOOTER_ALPHA = 0.38f

/**
 * A session that is open or opening: the tab strip, the terminal or the file browser, the
 * accessory bar, and in list mode the command palette. While it opens, [waiting] takes
 * the terminal's place with everything around it laid out and the keyboard up, so the
 * terminal's size is known before the far end is given one, and nothing is resized when
 * the terminal shows. [snapshot] is read only once [waiting] is null.
 */
@Composable
internal fun TerminalSessionContent(
    vm: TerminalViewModel,
    sessionState: SessionState,
    snapshot: () -> TerminalSnapshot,
    host: TerminalSessionHost,
    modifier: Modifier = Modifier,
    waiting: TerminalWaiting? = null,
) {
    val context = LocalContext.current
    val overlay = host.overlay
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val selectedPane by vm.files.selectedPane.collectAsStateWithLifecycle()
    // The terminal takes Esc as a key; the files pane and a session that still opens leave it to act as Back.
    HoldEscapeKey(enabled = waiting == null && selectedPane == ConnectionTab.Terminal)
    val input = remember(context, vm, overlay) { TerminalInputController(context, vm, overlay) }
    SideEffect {
        input.abysshKeys = host.abysshKeys
        input.ctrlVPastes = host.settings.ctrlVPastes
        input.tabMode = host.settings.tabMode
        input.allTabs = tabs
        input.routeTabs = host.routeTabs
        input.live = waiting == null
        input.tmuxPrefix.enabled = host.settings.tmuxPrefixKeys && activeTab?.spec?.usesTmuxControlMode == true
    }
    TerminalSessionEffects(vm, input, host.settings.ghosttyTheme, sessionState, activeTabId)
    val files = rememberFilesShortcut(vm, filesSupported = activeTab?.spec?.transport != Transport.LocalShell)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .blur(if (overlay.isSwitcherOpen) 10.dp else 0.dp)
                .imePadding(),
        ) {
            host.tabStrip?.invoke()
            when {
                waiting == null && host.tabStrip != null && tabs.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TerminalNoSessions(onNewConnection = host.onNewConnection)
                }
                waiting == null && selectedPane == ConnectionTab.Files && files.supported ->
                    TerminalFilesPane(vm, Modifier.weight(1f))
                // One call for a session that opens and one that is open, so the input view,
                // and with it the keyboard, stays when the terminal shows.
                else -> TerminalPane(vm, sessionState, snapshot, host, input, waiting, Modifier.weight(1f))
            }
            // The footer leaves only a small gap above its keys, so the terminal gets the height.
            if (waiting != null || selectedPane == ConnectionTab.Terminal) {
                // While the session opens the keys take their room, but there is nothing to type into.
                Box(if (waiting == null) Modifier else Modifier.alpha(WAITING_FOOTER_ALPHA).clearAndSetSemantics {}) {
                    TerminalFooter(vm, host, input, files)
                    if (waiting != null) Box(Modifier.matchParentSize().pointerInput(Unit) {})
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    if (overlay.showTabSheet) TerminalPaletteSheet(vm, host, input, files)
}

@Composable
private fun TerminalSessionEffects(
    vm: TerminalViewModel,
    input: TerminalInputController,
    theme: GhosttyTheme?,
    sessionState: SessionState,
    activeTabId: String?,
) {
    val colors = AbyssColors.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(sessionState.handle, activeTabId) { input.clearSelection() }
    LaunchedEffect(input) { input.watchClipboard() }
    FileTransferNotices(vm)
    val isDark = (theme?.background ?: colors.background).luminance() < 0.5f
    // Keyed on the tabs too, so a tab that opens while this screen shows, as tmux windows do, gets the colors.
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    LaunchedEffect(theme, colors, isDark, tabs) {
        vm.input.onColorSchemeChanged(isDark)
        vm.input.onDefaultColorsChanged(
            fg = (theme?.foreground ?: colors.textPrimary).toRgbIntArray(),
            bg = (theme?.background ?: colors.background).toRgbIntArray(),
            cursor = (theme?.cursorColor ?: colors.accent).toRgbIntArray(),
            palette = theme?.toTerminalPaletteBytes(),
        )
    }
    LaunchedEffect(sessionState.bellCount) {
        if (sessionState.bellCount > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** Opens the file browser, or says why it can't: the local shell has no SFTP. */
private class FilesShortcut(val supported: Boolean, val message: String?, val open: () -> Boolean)

@Composable
private fun rememberFilesShortcut(vm: TerminalViewModel, filesSupported: Boolean): FilesShortcut {
    val context = LocalContext.current
    val resources = LocalResources.current
    val selectedPane by vm.files.selectedPane.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(filesSupported, selectedPane) {
        if (filesSupported) {
            message = null
        } else if (selectedPane == ConnectionTab.Files) {
            vm.files.selectPane(ConnectionTab.Terminal)
        }
    }
    return FilesShortcut(filesSupported, message) {
        if (filesSupported) {
            vm.files.selectPane(ConnectionTab.Files)
        } else {
            val unsupported = resources.getString(R.string.terminal_files_unsupported_local)
            message = unsupported
            Toast.makeText(context, unsupported, Toast.LENGTH_SHORT).show()
        }
        filesSupported
    }
}

/**
 * Shows the keyboard while the terminal pane is on screen, and hides it when the pane
 * leaves for the file browser or another screen. The keyboard would otherwise stay up
 * after its input view is gone, with nothing to type into. The terminal hears that it
 * has the focus once it is [live].
 */
@Composable
private fun TerminalKeyboardEffect(vm: TerminalViewModel, input: TerminalInputController, live: Boolean) {
    // The Compose host view stays attached after the input view goes, so its token
    // still reaches the window that owns the keyboard.
    val hostView = LocalView.current
    LaunchedEffect(input) { input.requestFocus() }
    LaunchedEffect(input, live) { if (live) vm.input.onFocusChanged(true) }
    DisposableEffect(input, hostView) {
        onDispose { input.hideKeyboard(hostView.windowToken) }
    }
}

/**
 * Tells the connections that are about to open while the keyboard is [expected] and not
 * up yet, so they wait for it and open at the size the terminal has above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardPendingEffect(vm: TerminalViewModel, expected: Boolean) {
    val pending = expected && !WindowInsets.isImeVisible
    DisposableEffect(vm, pending) {
        vm.input.onKeyboardPending(pending)
        onDispose { vm.input.onKeyboardPending(false) }
    }
}

/**
 * Tells the active tab its terminal is on screen while the terminal screen is started, so
 * it builds snapshots and plays Kitty animations only then. This includes the time the tab
 * is still connecting or preparing: a tmux window captures its panes only once shown, so
 * waiting for its first snapshot would wait forever.
 */
@Composable
internal fun TerminalOnScreenEffect(vm: TerminalViewModel) {
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val engine = activeTab?.engine
    LifecycleStartEffect(engine) {
        engine?.addViewer()
        onStopOrDispose { engine?.removeViewer() }
    }
}

@Composable
private fun TerminalPane(
    vm: TerminalViewModel,
    sessionState: SessionState,
    snapshot: () -> TerminalSnapshot,
    host: TerminalSessionHost,
    input: TerminalInputController,
    waiting: TerminalWaiting?,
    modifier: Modifier = Modifier,
) {
    val searchState by vm.search.state.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    TerminalKeyboardEffect(vm, input, live = waiting == null)
    KeyboardPendingEffect(vm, expected = waiting != null && host.settings.usesOnScreenKeyboard)
    Column(modifier = modifier) {
        if (waiting == null) {
            searchState?.let { search ->
                TerminalSearchBar(
                    state = search,
                    onQueryChange = vm.search::setQuery,
                    onOlder = vm.search::older,
                    onNewer = vm.search::newer,
                    onClose = {
                        vm.search.close()
                        input.requestFocus()
                    },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            // Outside the branches below, so the view and its keyboard outlast the switch between them.
            TerminalInputHost(input, host.settings)
            if (waiting != null) {
                // Sizes the tab as its terminal will, so its connection opens at that size.
                TerminalGridProbe(
                    fontSizeSp = host.fontSize.floatValue,
                    ligatures = host.settings.ligatures,
                    onResize = vm.input::onCanvasSizeChanged,
                    resizeTarget = activeTab?.engine,
                )
                TerminalWaitingStatus(waiting, stripMode = host.tabStrip != null)
            } else {
                TerminalLayers(vm, sessionState, snapshot, host, input)
            }
        }
    }
}

/** The hidden view that takes the keyboard's input for the terminal. */
@Composable
private fun BoxScope.TerminalInputHost(input: TerminalInputController, settings: TerminalUiSettings) {
    val inputLabel = stringResource(R.string.terminal_input_label)
    AndroidView(
        modifier = Modifier.align(Alignment.BottomStart).size(1.dp).alpha(0f),
        factory = input::createInputView,
        update = { view ->
            view.contentDescription = inputLabel
            view.imeMode = terminalImeMode(settings.disableAutocorrect, settings.swipeTyping)
            view.volumeKeysAsArrows = settings.volumeKeysAsArrows
            view.usesOnScreenKeyboard = settings.usesOnScreenKeyboard
        },
        onRelease = input::releaseInputView,
    )
}

/** The terminal and what floats over it: the zoom and link prompts, the status badges, and the action buttons. */
@Composable
private fun BoxScope.TerminalLayers(
    vm: TerminalViewModel,
    sessionState: SessionState,
    snapshot: () -> TerminalSnapshot,
    host: TerminalSessionHost,
    input: TerminalInputController,
) {
    val colors = AbyssColors.current
    val theme = host.settings.ghosttyTheme
    val searchState by vm.search.state.collectAsStateWithLifecycle()
    val multiplexer by vm.multiplexer.state.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    var pinchZoomSteps by remember { mutableIntStateOf(0) }
    SideEffect {
        input.onViewKey = { viewKey ->
            when (viewKey) {
                is TerminalViewKey.ScrollPages -> vm.input.scrollPages(viewKey.pages)
                is TerminalViewKey.FontSize -> stepFontSize(host.fontSize, viewKey.step.toFloat())
                TerminalViewKey.ResetFontSize -> host.fontSize.floatValue = SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE
            }
            // Shows the new size, as pinch zoom does.
            if (viewKey !is TerminalViewKey.ScrollPages) pinchZoomSteps++
        }
    }
    TerminalZoomFrame(zoom = host.overlay.zoom, snapshot = snapshot, onTap = input::requestFocus) {
        TerminalCanvas(
            snapshot = snapshot,
            fontSizeSp = host.fontSize.floatValue,
            minFontSizeSp = SettingsRepository.MIN_TERMINAL_FONT_SIZE,
            maxFontSizeSp = SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            cursorColor = theme?.cursorColor ?: Color.White.copy(alpha = 0.28f),
            cursorTextColor = theme?.cursorText,
            selectionBackgroundColor = theme?.selectionBackground ?: colors.accent.copy(alpha = 0.45f),
            selectionForegroundColor = theme?.selectionForeground ?: colors.onAccent,
            selection = input.selection,
            onSelectionChange = { input.selection = it },
            selectionText = vm.search::selectionText,
            modifier = Modifier.fillMaxSize(),
            onResize = vm.input::onCanvasSizeChanged,
            resizeTarget = activeTab?.engine,
            onTap = input::requestFocus,
            onPrimaryClick = vm.input::onPrimaryMouseClick,
            onLinkTap = vm.search::onLinkTap,
            onSecondaryClick = vm.input::onSecondaryMouseClick,
            onMouseButton = vm.input::onMouseButton,
            onAppSelectionDrag = vm.input::onAppSelectionDrag,
            stickyModifiers = input.modifierState,
            onShiftSelectionStart = input::spendStickyShift,
            onRectangleSelectionStart = input::spendStickyAlt,
            wordSeparators = host.settings.wordSeparators,
            ligatures = host.settings.ligatures,
            cursorBlinkDefault = host.settings.cursorBlink,
            onScroll = vm.input::onScroll,
            onFontSizeChange = { sizeSp ->
                host.fontSize.floatValue = sizeSp
                pinchZoomSteps++
            },
            onSelectionChanged = { state -> input.selectionState = state },
            searchMatches = searchState?.matches.orEmpty(),
            currentSearchMatch = searchState?.currentMatch,
        )
    }
    TerminalZoomIndicator(
        text = stringResource(R.string.terminal_zoom_font_size, host.fontSize.floatValue.roundToInt()),
        changes = pinchZoomSteps,
        modifier = Modifier.align(Alignment.Center),
    )
    TerminalLinkPrompt(vm, Modifier.align(Alignment.BottomCenter))
    TerminalStatusBadges(
        sessionState = sessionState,
        preparingMultiplexer = multiplexer.duplicateLoading,
        onCancelReconnect = vm::cancelConnect,
        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
    )
    // Its handles and menu sit at unzoomed positions, which zoom mode moves away from.
    if (!host.overlay.zoom.active) TerminalSelectionOverlay(input)
    val settings = host.settings
    val showActionsFab = settings.customKeyGroups.isNotEmpty() &&
        (settings.showCustomActionsFab || host.overlay.fabFilteredActions != null)
    // The jump chip sits above the custom actions button, and above its menu when it is open.
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        TerminalJumpToBottom(
            scrolledBack = { snapshot().scrolledBack },
            onClick = vm.input::scrollToBottom,
            modifier = Modifier.padding(bottom = if (showActionsFab) 8.dp else 0.dp),
        )
        if (showActionsFab) {
            TerminalCustomActionsFab(
                groups = settings.customKeyGroups,
                expanded = host.overlay.fabExpanded,
                onExpandedChange = { host.overlay.fabExpanded = it },
                onActionClick = { action ->
                    vm.input.sendCustomAction(action)
                    input.requestFocus()
                },
                filteredActions = host.overlay.fabFilteredActions,
                onClearFilter = { host.overlay.fabFilteredActions = null },
            )
        }
    }
    AbysshShortcutMenu(host.abysshKeys, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp))
}

@Composable
private fun TerminalLinkPrompt(vm: TerminalViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tappedLink by vm.search.tappedLink.collectAsStateWithLifecycle()
    // Keeps showing the last link while the bar slides away after a dismiss.
    var url by remember { mutableStateOf("") }
    tappedLink?.let { url = it }
    AnimatedVisibility(
        visible = tappedLink != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        TerminalLinkBar(
            url = url,
            onOpen = if (isOpenableLink(url)) {
                {
                    vm.search.dismissLink()
                    openLink(context, url)
                }
            } else {
                null
            },
            onCopy = {
                vm.search.dismissLink()
                copyToClipboard(context, "link", url, R.string.terminal_copied_link)
            },
            onDismiss = vm.search::dismissLink,
        )
    }
}

// The schemes a tapped link may open. Anything else, such as file:, intent:, or an app's
// own scheme, comes from the remote side and can only be copied.
private val OPENABLE_LINK_SCHEMES = setOf("http", "https", "mailto")

internal fun isOpenableLink(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "")
    return scheme.lowercase() in OPENABLE_LINK_SCHEMES
}

private fun openLink(context: Context, url: String) {
    if (!isOpenableLink(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.resources.getString(R.string.terminal_link_no_app), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TerminalStatusBadges(
    sessionState: SessionState,
    preparingMultiplexer: Boolean,
    onCancelReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sessionState.status == SessionStatus.Reconnecting) {
            AbyssText(
                text = sessionState.reconnectAttempt.takeIf { it > 0 }
                    ?.let { stringResource(R.string.terminal_reconnecting_attempt, it) }
                    ?: stringResource(R.string.terminal_reconnecting),
                style = typography.labelSmall,
                color = colors.error,
            )
            // The old screen stays up while it reconnects, so this is the only way to stop.
            AbyssButton(
                onClick = onCancelReconnect,
                variant = AbyssButtonVariant.Ghost,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                AbyssText(stringResource(R.string.action_cancel), style = typography.labelSmall, color = colors.textMuted)
            }
        }
        if (preparingMultiplexer) {
            AbyssText(
                text = stringResource(R.string.terminal_preparing_multiplexer),
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
        sessionState.pwd?.takeIf { it.isNotBlank() }?.let { pwd ->
            AbyssText(text = pwd, style = typography.codeSmall, color = colors.textPrimary.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun TerminalFooter(
    vm: TerminalViewModel,
    host: TerminalSessionHost,
    input: TerminalInputController,
    files: FilesShortcut,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val activeHostCount = remember(tabs) { tabs.map { it.spec.hostId ?: it.spec.sessionKey }.distinct().size }
    val hostName = activeTab?.spec?.hostLabel()
    val shownHostName = hostName?.takeIf { activeHostCount > 1 }
    Column(modifier = Modifier.fillMaxWidth()) {
        shownHostName?.let { name ->
            // Trimmed to its glyphs, so the label adds little more than its text to the gap above the keys.
            AbyssText(
                text = name,
                style = typography.labelSmall.copy(
                    lineHeight = typography.labelSmall.fontSize,
                    lineHeightStyle = TIGHT_LINE_HEIGHT,
                    textAlign = TextAlign.End,
                ),
                color = colors.textMuted.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
            )
        }
        files.message?.let { message ->
            AbyssText(
                text = message,
                style = typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        KeyboardAccessoryBar(
            entries = host.settings.accessoryLayout,
            modifierState = input.modifierState,
            capsLockOn = input.capsLockOn,
            onAction = { action -> input.dispatchAccessoryAction(action) },
            onZoom = host.overlay.zoom::toggle,
            zoomActive = host.overlay.zoom.active,
            onAbysshKey = {
                host.abysshKeys.togglePrefix()
                input.requestFocus()
            },
            abysshKeyActive = host.abysshKeys.isPrefixActive,
            onOpenFiles = { files.open() },
            onEndSession = host.onEndSession,
            endSessionLabel = activeTab?.let { stringResource(endTabLabel(it)) },
            useSingleRow = host.settings.singleRowAccessoryBar,
            topPadding = if (shownHostName != null) 3.dp else 2.dp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

private val TIGHT_LINE_HEIGHT = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)

/** List mode's tab switcher, with the accessory bar driving its tab list. */
@Composable
private fun TerminalPaletteSheet(
    vm: TerminalViewModel,
    host: TerminalSessionHost,
    input: TerminalInputController,
    files: FilesShortcut,
) {
    val overlay = host.overlay
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    CommandPalette(
        tabs = host.routeTabs,
        activeTabId = activeTabId,
        focusedTabIndex = overlay.focusedTabIndex,
        onFocusedTabIndexChange = { overlay.focusedTabIndex = it },
        onSearch = {
            vm.files.selectPane(ConnectionTab.Terminal)
            vm.search.open()
            overlay.showTabSheet = false
        },
        onSelectTab = { id ->
            vm.selectTab(id)
            overlay.showTabSheet = false
        },
        onCloseTab = host.onCloseTab,
        onAddTab = {
            vm.duplicateTab()
            overlay.showTabSheet = false
        },
        onDismiss = { overlay.showTabSheet = false },
        accessoryBar = { barModifier ->
            KeyboardAccessoryBar(
                entries = host.settings.accessoryLayout,
                modifierState = input.modifierState,
                capsLockOn = input.capsLockOn,
                onAction = { action -> input.dispatchAccessoryAction(action, inSwitcher = true) },
                onAbysshKey = host.abysshKeys::togglePrefix,
                abysshKeyActive = host.abysshKeys.isPrefixActive,
                onOpenFiles = { if (files.open()) overlay.showTabSheet = false },
                useSingleRow = host.settings.singleRowAccessoryBar,
                modifier = barModifier,
            )
        },
        multiplexerPanel = { TerminalMultiplexerPanel(vm, onDismiss = { overlay.showTabSheet = false }) },
    )
}

private fun stepFontSize(fontSize: MutableFloatState, step: Float) {
    fontSize.floatValue = (fontSize.floatValue.roundToInt() + step)
        .coerceIn(SettingsRepository.MIN_TERMINAL_FONT_SIZE, SettingsRepository.MAX_TERMINAL_FONT_SIZE)
}
