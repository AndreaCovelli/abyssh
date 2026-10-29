package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.core.net.toUri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings
import io.github.andreacovelli.abyssh.ui.terminal.KeyboardAccessoryBar
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCanvas
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import io.github.andreacovelli.abyssh.ui.theme.GhosttyTheme
import io.github.andreacovelli.abyssh.ui.theme.toRgbIntArray
import io.github.andreacovelli.abyssh.ui.theme.toTerminalPaletteBytes
import kotlin.math.roundToInt

/**
 * A connected session: the tab strip, the terminal or the file browser, the accessory
 * bar, and in list mode the command palette.
 */
@Composable
internal fun TerminalSessionContent(
    vm: TerminalViewModel,
    sessionState: SessionState,
    snapshot: () -> TerminalSnapshot,
    host: TerminalSessionHost,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val overlay = host.overlay
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val selectedPane by vm.files.selectedPane.collectAsStateWithLifecycle()
    val fileState by vm.files.state.collectAsStateWithLifecycle()
    val input = remember(context, vm, overlay) { TerminalInputController(context, vm, overlay) }
    SideEffect {
        input.abysshKeys = host.abysshKeys
        input.ctrlVPastes = host.settings.ctrlVPastes
        input.tabMode = host.settings.tabMode
        input.allTabs = tabs
        input.routeTabs = host.routeTabs
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
                host.tabStrip != null && tabs.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TerminalNoSessions(onNewConnection = host.onNewConnection)
                }
                selectedPane == ConnectionTab.Files && files.supported -> TerminalFilesPane(vm, Modifier.weight(1f))
                else -> TerminalPane(vm, sessionState, snapshot, host, input, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (selectedPane == ConnectionTab.Terminal) TerminalFooter(vm, host, input, files)
        }
    }

    fileState.uploadProgress?.let { UploadProgressDialog(progress = it) }
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
    val isDark = (theme?.background ?: colors.background).luminance() < 0.5f
    LaunchedEffect(theme, colors, isDark) {
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
 * after its input view is gone, with nothing to type into.
 */
@Composable
private fun TerminalKeyboardEffect(vm: TerminalViewModel, input: TerminalInputController) {
    // The Compose host view stays attached after the input view goes, so its token
    // still reaches the window that owns the keyboard.
    val hostView = LocalView.current
    LaunchedEffect(input) {
        input.requestFocus()
        vm.input.onFocusChanged(true)
    }
    DisposableEffect(input, hostView) {
        onDispose { input.hideKeyboard(hostView.windowToken) }
    }
}

/**
 * Tells the active tab its terminal is on screen while the pane is shown and the app is
 * started, so its Kitty animations play only then.
 */
@Composable
private fun TerminalOnScreenEffect(vm: TerminalViewModel) {
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val engine = activeTab?.engine
    LifecycleStartEffect(engine) {
        engine?.setOnScreen(true)
        onStopOrDispose { engine?.setOnScreen(false) }
    }
}

@Composable
private fun TerminalPane(
    vm: TerminalViewModel,
    sessionState: SessionState,
    snapshot: () -> TerminalSnapshot,
    host: TerminalSessionHost,
    input: TerminalInputController,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val theme = host.settings.ghosttyTheme
    val searchState by vm.search.state.collectAsStateWithLifecycle()
    val multiplexer by vm.multiplexer.state.collectAsStateWithLifecycle()
    TerminalKeyboardEffect(vm, input)
    TerminalOnScreenEffect(vm)
    Column(modifier = modifier) {
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
        Box(modifier = Modifier.weight(1f)) {
            var pinchZoomSteps by remember { mutableIntStateOf(0) }
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
                    onTap = input::requestFocus,
                    onPrimaryClick = vm.input::onPrimaryMouseClick,
                    onLinkTap = vm.search::onLinkTap,
                    onSecondaryClick = vm.input::onSecondaryMouseClick,
                    onMouseButton = vm.input::onMouseButton,
                    onAppSelectionDrag = vm.input::onAppSelectionDrag,
                    shiftActive = input.modifierState.shift,
                    onShiftSelectionStart = input::spendStickyShift,
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
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
            // Its handles and menu sit at unzoomed positions, which zoom mode moves away from.
            if (!host.overlay.zoom.active) TerminalSelectionOverlay(input)
            AndroidView(
                modifier = Modifier.align(Alignment.BottomStart).size(1.dp).alpha(0f),
                factory = input::createInputView,
                update = { view -> view.disableAutocorrect = host.settings.disableAutocorrect },
                onRelease = input::releaseInputView,
            )
            val settings = host.settings
            if (settings.customKeyGroups.isNotEmpty() &&
                (settings.showCustomActionsFab || host.overlay.fabFilteredActions != null)
            ) {
                TerminalCustomActionsFab(
                    groups = settings.customKeyGroups,
                    onActionClick = { action ->
                        vm.input.sendCustomAction(action)
                        input.requestFocus()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp),
                    filteredActions = host.overlay.fabFilteredActions,
                    onClearFilter = { host.overlay.fabFilteredActions = null },
                )
            }
            AbysshPrefixHints(host.abysshKeys, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
    }
}

@Composable
private fun TerminalLinkPrompt(vm: TerminalViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tappedLink by vm.search.tappedLink.collectAsStateWithLifecycle()
    val url = tappedLink ?: return
    TerminalLinkBar(
        url = url,
        onOpen = {
            vm.search.dismissLink()
            openLink(context, url)
        },
        onCopy = {
            vm.search.dismissLink()
            copyToClipboard(context, "link", url, R.string.terminal_copied_link)
        },
        onDismiss = vm.search::dismissLink,
        modifier = modifier,
    )
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.resources.getString(R.string.terminal_link_no_app), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TerminalStatusBadges(sessionState: SessionState, preparingMultiplexer: Boolean, modifier: Modifier = Modifier) {
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

/** While the ⌘ prefix waits for its second key, the keys it takes and what they do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbysshPrefixHints(keys: AbysshKeyBindings, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val shape = MaterialTheme.shapes.medium
    AnimatedVisibility(visible = keys.isPrefixActive, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssText("⌘", style = typography.label, color = colors.accent, modifier = Modifier.width(24.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                keys.hints().forEach { hint ->
                    AbyssText("${hint.key}: ${hint.description}", style = typography.labelSmall, color = colors.textSecondary)
                }
            }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        if (activeHostCount > 1 && hostName != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                AbyssText(text = hostName, style = typography.labelSmall, color = colors.textMuted.copy(alpha = 0.7f))
            }
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
            useSingleRow = host.settings.singleRowAccessoryBar,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

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
        onCloseTab = vm::closeTab,
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
