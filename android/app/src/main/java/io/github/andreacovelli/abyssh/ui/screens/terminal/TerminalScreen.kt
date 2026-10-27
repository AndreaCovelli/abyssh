package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserScreen
import io.github.andreacovelli.abyssh.ui.screens.files.UploadProgress
import io.github.andreacovelli.abyssh.ui.screens.files.formatFileSize
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.AccessoryAction
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings
import io.github.andreacovelli.abyssh.ui.terminal.CustomActionModifier
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKey
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.KeyboardAccessoryBar
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryDispatcher
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCanvas
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.terminal.TerminalInputView
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelection
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionHandle
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey
import io.github.andreacovelli.abyssh.ui.terminal.decodeCustomActionValue
import io.github.andreacovelli.abyssh.ui.terminal.isPasteShortcut
import io.github.andreacovelli.abyssh.ui.terminal.isSearchShortcut
import io.github.andreacovelli.abyssh.ui.terminal.modifierStateForCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.toGhosttyKey
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import io.github.andreacovelli.abyssh.ui.theme.resolveActiveThemeName
import io.github.andreacovelli.abyssh.ui.theme.toRgbIntArray
import io.github.andreacovelli.abyssh.ui.theme.toTerminalPaletteBytes
import io.github.andreacovelli.abyssh.ui.security.requireUserVerification
import io.github.andreacovelli.abyssh.ui.security.VerificationResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun TerminalViewModel.dispatchTextWithModifierState(
    text: String,
    modifierState: ModifierState,
) {
    val mods = modifierState.terminalMods()
    for (char in text) {
        val ghosttyKey =
            when (char) {
                '\r',
                '\n' -> GhosttyKey.enter
                '\t' -> GhosttyKey.tab
                else -> char.toGhosttyKey()
            }
        if (ghosttyKey != null) {
            val codepoint =
                when (char) {
                    '\r',
                    '\n',
                    '\t' -> 0
                    else -> char.code
                }
            onHardwareKey(ghosttyKey, codepoint, mods, GhosttyKeyAction.Press)
            onHardwareKey(ghosttyKey, codepoint, mods, GhosttyKeyAction.Release)
        } else {
            onTextInput(modifierState.applyToText(char.toString()))
        }
    }
}

@Composable
private fun NewTabHostPicker(
    currentHostName: String,
    otherHosts: List<HostProfile>,
    onCurrentHost: () -> Unit,
    onHostSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val density = LocalDensity.current

    Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { IntOffset(0, 36.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier =
                Modifier.width(320.dp)
                    .background(colors.background)
                    .border(1.dp, colors.border)
                    .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AbyssButton(
                onClick = onCurrentHost,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Ghost,
                bracketed = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                AbyssText(
                    stringResource(R.string.terminal_new_session_on, currentHostName),
                    style = typography.label,
                    color = colors.textPrimary,
                )
            }

            if (otherHosts.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(otherHosts, key = { it.id }) { host ->
                        AbyssButton(
                            onClick = { onHostSelected(host.id) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = AbyssButtonVariant.Ghost,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                AbyssText(
                                    host.name,
                                    style = typography.label,
                                    color = colors.textPrimary,
                                )
                                AbyssText(
                                    "${host.username}@${host.host}:${host.port}",
                                    style = typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalCustomActionsFab(
    groups: List<TerminalCustomKeyGroup>,
    onActionClick: (TerminalCustomAction) -> Unit,
    modifier: Modifier = Modifier,
    filteredActions: List<TerminalCustomAction>? = null,
    onClearFilter: () -> Unit = {},
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var expanded by remember { mutableStateOf(filteredActions != null) }
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup =
        remember(selectedGroupKey, groups) {
            groups.firstOrNull { it.keyLabel == selectedGroupKey }
        }

    LaunchedEffect(filteredActions) {
        if (filteredActions != null) {
            expanded = true
            selectedGroupKey = null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filteredActions != null) {
                    filteredActions.forEach { action ->
                        AbyssButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                onClearFilter()
                            },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(action.label, style = typography.label)
                        }
                    }
                    AbyssButton(
                        onClick = {
                            expanded = false
                            onClearFilter()
                        },
                        variant = AbyssButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        AbyssText("<", style = typography.label, color = colors.textMuted)
                    }
                } else if (selectedGroup == null) {
                    groups.forEach { group ->
                        AbyssButton(
                            onClick = {
                                if (group.actions.size == 1) {
                                    onActionClick(group.actions.first())
                                    expanded = false
                                    selectedGroupKey = null
                                } else {
                                    selectedGroupKey = group.keyLabel
                                }
                            },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(group.keyLabel, style = typography.label)
                        }
                    }
                } else {
                    selectedGroup.actions.forEach { action ->
                        AbyssButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                selectedGroupKey = null
                            },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(action.label, style = typography.label)
                        }
                    }
                    AbyssButton(
                        onClick = { selectedGroupKey = null },
                        variant = AbyssButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        AbyssText("<", style = typography.label, color = colors.textMuted)
                    }
                }
            }
        }

        // TUI-style action toggle — square bracketed button instead of a
        // circular Material FAB. Reads like a macro/quick-key trigger.
        AbyssButton(
            onClick = {
                expanded = !expanded
                if (!expanded) {
                    selectedGroupKey = null
                    onClearFilter()
                }
            },
            variant = if (expanded) AbyssButtonVariant.Ghost else AbyssButtonVariant.Filled,
            bracketed = true,
            borderColor = if (expanded) colors.textMuted else null,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AbyssText(
                if (expanded) "x" else "+",
                style = typography.label,
                color = if (expanded) colors.textMuted else colors.onAccent,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    hostId: Long?,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    openLocalShell: Boolean = false,
) {
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val activeTabForHost =
        remember(activeTab, hostId, openLocalShell) {
            if (openLocalShell) {
                activeTab?.takeIf {
                    it.spec.hostId == null && it.spec.transport == Transport.LocalShell
                }
            } else {
                activeTab?.takeIf { it.spec.hostId == hostId }
            }
        }
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val filesSupported = activeTab?.spec?.transport != Transport.LocalShell
    val fileBrowserState by vm.fileBrowserState.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val searchState by vm.search.collectAsStateWithLifecycle()
    val tappedLink by vm.tappedLink.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    val settingsRepo = remember(context) { SettingsRepository.getInstance(context) }
    val currentTheme by settingsRepo.themeName.collectAsStateWithLifecycle()
    val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
    val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
    val resolvedThemeName = resolveActiveThemeName(
        themeMode = themeMode,
        darkThemeName = currentTheme,
        lightThemeName = lightThemeName,
    )
    val tabMode by settingsRepo.terminalTabMode.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val useSingleRowAccessoryBar by settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
    val currentTerminalCustomKeyGroups by
        settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
    val settingsFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
    val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
    val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
    val ctrlVPastes by settingsRepo.ctrlVPastes.collectAsStateWithLifecycle()
    val keepAwakeView = LocalView.current
    DisposableEffect(keepScreenAwake, keepAwakeView) {
        val window = (keepAwakeView.context as? Activity)?.window
        if (keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val accessoryLayout =
        remember(currentAccessoryLayoutIds) {
            TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
        }
    val ghosttyTheme =
        remember(context, resolvedThemeName) { GhosttyThemeRegistry.getTheme(context, resolvedThemeName) }
    val isDarkTheme = (ghosttyTheme?.background ?: colors.background).luminance() < 0.5f
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var selectionState by remember { mutableStateOf<TerminalSelectionState?>(null) }
    var showPassphrasePrompt by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var pendingTabSpec by remember { mutableStateOf<TabSpec?>(null) }
    var passphraseFromPicker by remember { mutableStateOf(false) }
    var showTabSheet by remember { mutableStateOf(false) }
    var showGlobalTabManager by remember { mutableStateOf(false) }
    var showNewTabPicker by remember { mutableStateOf(false) }
    var hasSeenTabsForHost by remember(hostId, openLocalShell) { mutableStateOf(false) }
    var focusedTabIndex by remember { mutableStateOf(0) }
    var localShellFilesMessage by remember { mutableStateOf<String?>(null) }
    var terminalFontSizeSp by remember {
        mutableStateOf(
            settingsFontSize.coerceIn(
                SettingsRepository.MIN_TERMINAL_FONT_SIZE,
                SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            ),
        )
    }
    LaunchedEffect(settingsFontSize) {
        terminalFontSizeSp =
            settingsFontSize.coerceIn(
                SettingsRepository.MIN_TERMINAL_FONT_SIZE,
                SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            )
    }
    val showCustomActionsFab by settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
    val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
    var fabFilteredActions by remember { mutableStateOf<List<TerminalCustomAction>?>(null) }
    val abysshKeys =
        remember(vm, tabMode, currentTerminalCustomKeyGroups, builtinShortcuts, showCustomActionsFab) {
            val isStrip = tabMode == TerminalTabMode.Strip
            val builtinCommandHandlers: Map<BuiltinCommand, () -> Unit> = mapOf(
                BuiltinCommand.Tabs to {
                    if (isStrip) {
                        showGlobalTabManager = true
                    } else {
                        showTabSheet = true
                    }
                },
                BuiltinCommand.NewTab to {
                    vm.duplicateActiveTab()
                    vm.selectConnectionTab(ConnectionTab.Terminal)
                    showTabSheet = false
                },
                BuiltinCommand.Close to {
                    val activeId = vm.activeTabId.value
                    if (activeId != null) vm.closeTab(activeId)
                },
                BuiltinCommand.Actions to { settingsRepo.setShowCustomActionsFab(!showCustomActionsFab) },
                BuiltinCommand.Settings to { onOpenSettings() },
                BuiltinCommand.Search to {
                    vm.selectConnectionTab(ConnectionTab.Terminal)
                    vm.openSearch()
                },
            )
            AbysshKeyBindings.build(
                builtinShortcuts = builtinShortcuts,
                builtinCommandHandlers = builtinCommandHandlers,
                customGroups = currentTerminalCustomKeyGroups,
                onDispatchAction = { action ->
                    val decoded = decodeCustomActionValue(action.payload)
                    val rawText = decoded.text +
                        if (CustomActionModifier.Enter in decoded.modifiers) "\n" else ""
                    val actionModifierState = modifierStateForCustomAction(decoded.modifiers)
                    vm.dispatchTextWithModifierState(rawText, actionModifierState)
                },
                onSelectAmongActions = { actions -> fabFilteredActions = actions },
            )
        }
    val multiplexerState by vm.multiplexerState.collectAsStateWithLifecycle()

    LaunchedEffect(terminalFontSizeSp) {
        settingsRepo.setTerminalFontSize(terminalFontSizeSp)
    }

    LaunchedEffect(openLocalShell) {
        if (!openLocalShell) return@LaunchedEffect
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        passphraseFromPicker = false
        val existing = vm.selectLocalShellTab()
        if (existing != null) {
            return@LaunchedEffect
        }
        vm.openTab(
            TabSpec(
                hostId = null,
                displayName = "local shell",
                host = "localhost",
                username = "android",
                authMethod = AuthMethod.None,
                transport = Transport.LocalShell,
            )
        )
    }


    val hasTabsForHost =
        remember(tabs, hostId, openLocalShell) {
            when {
                openLocalShell ->
                    tabs.any { it.spec.hostId == null && it.spec.transport == Transport.LocalShell }
                hostId != null -> tabs.any { it.spec.hostId == hostId }
                else -> false
            }
        }
    val tabsForHost =
        remember(tabs, hostId, openLocalShell) {
            when {
                openLocalShell ->
                    tabs.filter { it.spec.hostId == null && it.spec.transport == Transport.LocalShell }
                hostId != null -> tabs.filter { it.spec.hostId == hostId }
                else -> emptyList()
            }
        }
    val activeHostCount =
        remember(tabs) {
            tabs.map { it.spec.hostId ?: it.spec.sessionKey }
                .distinct()
                .size
        }
    val currentHostName = activeTab?.spec?.displayName?.takeIf { it.isNotBlank() }
        ?: activeTab?.spec?.host?.takeIf { it.isNotBlank() }
    val pickerScope = rememberCoroutineScope()

    val openPreparedTab: (TabSpec, Boolean, Boolean) -> Unit = { spec, requiresVerification, fromPicker ->
        val openOrPrompt: (TabSpec) -> Unit = { preparedSpec ->
            if (
                preparedSpec.authMethod == AuthMethod.KeyWithPassphrase &&
                    preparedSpec.keyPassphrase.isBlank()
            ) {
                passphraseFromPicker = fromPicker
                pendingTabSpec = preparedSpec
                showPassphrasePrompt = true
            } else if (preparedSpec.usesRuntimeMultiplexer) {
                vm.initiateMultiplexerOpen(preparedSpec)
            } else {
                vm.openTab(preparedSpec)
            }
        }

        if (requiresVerification) {
            requireUserVerification(
                context = context,
                title = resources.getString(R.string.terminal_verify_title),
                subtitle = resources.getString(R.string.terminal_verify_subtitle),
            ) { result ->
                if (
                    result == VerificationResult.Success &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    openOrPrompt(spec)
                }
            }
        } else {
            openOrPrompt(spec)
        }
    }

    val openAnotherSessionForCurrentHost: () -> Unit = {
        val currentHostId = activeTab?.spec?.hostId ?: hostId
        when {
            activeTab != null -> {
                vm.duplicateActiveTab()
                vm.selectConnectionTab(ConnectionTab.Terminal)
            }

            currentHostId != null -> {
                pickerScope.launch(Dispatchers.IO) {
                    val prepared = vm.prepareTabOpenForHost(currentHostId) ?: return@launch
                    withContext(Dispatchers.Main) {
                        openPreparedTab(prepared.spec, prepared.requiresVerification, false)
                    }
                }
            }

        }
    }
    val pickerCurrentHostId = activeTab?.spec?.hostId ?: hostId
    val pickerCurrentHostName =
        currentHostName ?: hosts.firstOrNull { it.id == pickerCurrentHostId }?.name ?: stringResource(R.string.terminal_current_host)
    val pickerOtherHosts =
        remember(hosts, pickerCurrentHostId) { hosts.filter { it.id != pickerCurrentHostId } }
    val openNewSessionForHost: (Long) -> Unit = { pickerHostId ->
        pickerScope.launch(Dispatchers.IO) {
            val prepared = vm.prepareTabOpenForHost(pickerHostId) ?: return@launch
            withContext(Dispatchers.Main) {
                openPreparedTab(prepared.spec, prepared.requiresVerification, false)
            }
        }
    }
    val newTabPicker: @Composable () -> Unit = {
        if (showNewTabPicker) {
            NewTabHostPicker(
                currentHostName = pickerCurrentHostName,
                otherHosts = pickerOtherHosts,
                onCurrentHost = {
                    showNewTabPicker = false
                    openAnotherSessionForCurrentHost()
                },
                onHostSelected = { pickerHostId ->
                    showNewTabPicker = false
                    openNewSessionForHost(pickerHostId)
                },
                onDismiss = { showNewTabPicker = false },
            )
        }
    }

    LaunchedEffect(hostId, openLocalShell) {
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        passphraseFromPicker = false
        if (hostId == null || openLocalShell) return@LaunchedEffect
        val existing = vm.selectTabForHost(hostId)
        if (existing != null) {
            return@LaunchedEffect
        }
        val prepared = vm.prepareTabOpenForHost(hostId) ?: return@LaunchedEffect
        vm.refreshTailscaleStatus()
        // Biometric verification already happened in ApplicationNavController
        // before navigating to this route — skip the redundant re-prompt.
        openPreparedTab(prepared.spec, false, false)
    }

    // Strip mode: never auto-back from normal host-scoped empty state.
    // Local-shell routes still close when their local tab is gone.
    LaunchedEffect(hostId, hasTabsForHost, openLocalShell, tabMode) {
        if (openLocalShell) {
            if (hasTabsForHost) {
                hasSeenTabsForHost = true
            } else if (hasSeenTabsForHost) {
                onBack()
            }
            return@LaunchedEffect
        }
        if (tabMode == TerminalTabMode.Strip) return@LaunchedEffect
        if (hostId == null) return@LaunchedEffect
        if (hasTabsForHost) {
            hasSeenTabsForHost = true
        } else if (hasSeenTabsForHost) {
            onBack()
        }
    }

    LaunchedEffect(showTabSheet, tabsForHost, activeTabId) {
        if (!showTabSheet || tabsForHost.isEmpty()) return@LaunchedEffect
        val activeIndex = tabsForHost.indexOfFirst { it.id == activeTabId }
        focusedTabIndex =
            if (activeIndex >= 0) activeIndex else focusedTabIndex.coerceIn(0, tabsForHost.lastIndex)
    }

    LaunchedEffect(showTabSheet, activeTab?.id) {
        if (showTabSheet && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
        }
    }

    LaunchedEffect(showGlobalTabManager, activeTab?.id) {
        if (showGlobalTabManager && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
        }
    }

    if (showPassphrasePrompt) {
        AbyssDialog(
            title = stringResource(R.string.terminal_key_passphrase_title),
            confirmLabel = stringResource(R.string.action_connect),
            onConfirm = {
                val spec = pendingTabSpec
                showPassphrasePrompt = false
                if (spec != null) {
                    val preparedSpec = spec.copy(keyPassphrase = passphraseInput)
                    if (preparedSpec.usesRuntimeMultiplexer) {
                        vm.initiateMultiplexerOpen(preparedSpec)
                    } else {
                        vm.openTab(preparedSpec)
                    }
                }
                passphraseInput = ""
                pendingTabSpec = null
                passphraseFromPicker = false
            },
            onDismiss = {
                showPassphrasePrompt = false
                passphraseInput = ""
                pendingTabSpec = null
                if (!passphraseFromPicker) {
                    onBack()
                }
                passphraseFromPicker = false
            },
        ) {
            AbyssTextField(
                value = passphraseInput,
                onValueChange = { passphraseInput = it },
                label = stringResource(R.string.key_passphrase_label),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    fun showLocalShellFilesUnsupported() {
        val message = resources.getString(R.string.terminal_files_unsupported_local)
        localShellFilesMessage = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(filesSupported, selectedTab) {
        if (filesSupported) {
            localShellFilesMessage = null
        }
        if (!filesSupported && selectedTab == ConnectionTab.Files) {
            vm.selectConnectionTab(ConnectionTab.Terminal)
        }
    }

    LaunchedEffect(sessionState.status, sessionState.error) {
        val previous = lastSessionStatus
        lastSessionStatus = sessionState.status
        if (sessionState.status == SessionStatus.Error && previous != SessionStatus.Error) {
            val message = sessionState.error ?: resources.getString(R.string.terminal_connection_failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    if (hostKeyPrompt != null) {
        val prompt = hostKeyPrompt
        AbyssDialog(
            onDismiss = { vm.onHostKeyDecision(false) },
            title = stringResource(R.string.terminal_host_key_title),
            confirmLabel = stringResource(R.string.action_accept),
            dismissLabel = stringResource(R.string.action_reject),
            onConfirm = { vm.onHostKeyDecision(true) },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            val previous = prompt?.previousFingerprint
            val message = buildString {
                appendLine(stringResource(R.string.terminal_host_key_host, prompt?.host.orEmpty(), prompt?.port ?: 0))
                appendLine(stringResource(R.string.terminal_host_key_algorithm, prompt?.algorithm.orEmpty()))
                if (previous != null) {
                    appendLine(stringResource(R.string.terminal_host_key_changed))
                    appendLine(stringResource(R.string.terminal_host_key_old, previous))
                }
                append(stringResource(R.string.terminal_host_key_new, prompt?.fingerprint.orEmpty()))
            }
            AbyssText(message, style = typography.body)
        }
    }

    val preflightError = multiplexerState.preflightError
    LaunchedEffect(preflightError) {
        if (preflightError != null) {
            Toast.makeText(context, preflightError, Toast.LENGTH_LONG).show()
        }
    }
    val displaySessionStatus =
        if (multiplexerState.preflightLoading) SessionStatus.Connecting else sessionState.status
    when (displaySessionStatus) {
        SessionStatus.Disconnected,
        SessionStatus.Error -> {
            if (tabMode == TerminalTabMode.Strip) {
                Column(modifier = screenInsetsModifier.fillMaxSize()) {
                    TerminalTabStrip(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onTabSelected = { id -> vm.selectTab(id) },
                        onAddTab = { showNewTabPicker = true },
                        onOpenManager = { showGlobalTabManager = true },
                        newTabPicker = newTabPicker,
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val errorMessage = preflightError ?: sessionState.error
                        if (errorMessage != null) {
                            TerminalRecoveryActions(
                                message = errorMessage,
                                isMultiplexerPreflight = preflightError != null,
                                dismissMultiplexerLabel = stringResource(if (multiplexerState.reconnectRecovery) R.string.action_dismiss else R.string.action_back),
                                onRetryMultiplexer = vm::retryPendingMultiplexerOpen,
                                onConnectWithoutMultiplexer = {
                                    if (vm.connectPendingWithoutMultiplexer()) {
                                        vm.selectConnectionTab(ConnectionTab.Terminal)
                                    }
                                },
                                onBack = { vm.dismissMultiplexerRecovery(onBack) },
                                onReconnect = vm::reconnect,
                            )
                        } else if (tabs.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AbyssText(
                                    stringResource(R.string.terminal_no_sessions),
                                    style = typography.body,
                                    color = colors.textMuted,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                AbyssButton(
                                    onClick = openAnotherSessionForCurrentHost,
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                    variant = AbyssButtonVariant.Outlined,
                                    bracketed = true,
                                ) {
                                    AbyssText(
                                        stringResource(R.string.terminal_new_connection),
                                        style = typography.label,
                                        color = colors.accent,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val errorMessage = preflightError ?: sessionState.error
                    if (errorMessage != null) {
                        TerminalRecoveryActions(
                            message = errorMessage,
                            isMultiplexerPreflight = preflightError != null,
                            dismissMultiplexerLabel = stringResource(if (multiplexerState.reconnectRecovery) R.string.action_dismiss else R.string.action_back),
                            onRetryMultiplexer = vm::retryPendingMultiplexerOpen,
                            onConnectWithoutMultiplexer = {
                                if (vm.connectPendingWithoutMultiplexer()) {
                                    vm.selectConnectionTab(ConnectionTab.Terminal)
                                }
                            },
                            onBack = { vm.dismissMultiplexerRecovery(onBack) },
                            onReconnect = vm::reconnect,
                        )
                    }
                }
            }
        }

        SessionStatus.Connecting -> {
            val hostLabel = if (multiplexerState.preflightLoading) {
                "${multiplexerState.preflightHostLabel ?: stringResource(R.string.terminal_host_fallback)}..."
            } else if (tabMode == TerminalTabMode.Strip) {
                activeTab?.spec?.tabLabel?.let { "$it..." } ?: "..."
            } else {
                activeTabForHost?.spec?.host?.let { "$it..." } ?: "..."
            }
            if (tabMode == TerminalTabMode.Strip) {
                Column(modifier = screenInsetsModifier.fillMaxSize()) {
                    TerminalTabStrip(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onTabSelected = { id -> vm.selectTab(id) },
                        onAddTab = { showNewTabPicker = true },
                        onOpenManager = { showGlobalTabManager = true },
                        newTabPicker = newTabPicker,
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AbyssText(
                            stringResource(R.string.terminal_connecting_to, hostLabel),
                            style = typography.body,
                        )
                    }
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AbyssText(
                        stringResource(R.string.terminal_connecting_to, hostLabel),
                        style = typography.body,
                    )
                }
            }
        }

        SessionStatus.Connected,
        SessionStatus.Reconnecting -> {
            val isReconnecting = sessionState.status == SessionStatus.Reconnecting
            val snapshot = sessionState.snapshot
            if (snapshot != null) {
                var modifierState by remember { mutableStateOf(ModifierState()) }
                val inputViewRef = remember { mutableStateOf<TerminalInputView?>(null) }
                var menuSize by remember { mutableStateOf(IntSize.Zero) }
                val clipboard = remember {
                    context.getSystemService(ClipboardManager::class.java)
                }
                LaunchedEffect(sessionState.handle, activeTabId) {
                    selection = null
                    selectionState = null
                    menuSize = IntSize.Zero
                }

                // Keyboard shortcuts paste the text as-is: the held Ctrl belongs
                // to the shortcut, not to the first pasted character.
                fun pasteClipboard(applyModifiers: Boolean = true): Boolean {
                    val clip = clipboard?.primaryClip
                    if (clip == null || clip.itemCount == 0) {
                        return false
                    }
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    if (text.isNotEmpty()) {
                        vm.onPasteText(if (applyModifiers) modifierState.applyToText(text) else text)
                        selection = null
                        selectionState = null
                        return true
                    }
                    return false
                }

                Box(modifier = screenInsetsModifier.fillMaxSize()) {
                    LaunchedEffect(ghosttyTheme, colors, isDarkTheme) {
                        vm.onColorSchemeChanged(isDarkTheme)
                        vm.onDefaultColorsChanged(
                            fg =
                                ghosttyTheme?.foreground?.toRgbIntArray()
                                    ?: colors.textPrimary.toRgbIntArray(),
                            bg =
                                ghosttyTheme?.background?.toRgbIntArray()
                                    ?: colors.background.toRgbIntArray(),
                            cursor =
                                ghosttyTheme?.cursorColor?.toRgbIntArray()
                                    ?: colors.accent.toRgbIntArray(),
                            palette = ghosttyTheme?.toTerminalPaletteBytes(),
                        )
                    }

                    LaunchedEffect(sessionState.bellCount) {
                        if (sessionState.bellCount > 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }

                    val titleText = sessionState.title?.takeIf { it.isNotBlank() }
                    val pwdText = sessionState.pwd?.takeIf { it.isNotBlank() }
                    val inputMethodManager = remember {
                        context.getSystemService(InputMethodManager::class.java)
                    }
                    val requestInputFocus: () -> Unit = {
                        inputViewRef.value?.let { view -> view.showKeyboard(inputMethodManager) }
                    }
                    val hideSoftKeyboard: () -> Unit = {
                        val view = inputViewRef.value
                        if (view != null) {
                            inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }
                    var hasClipboardText by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(clipboard) {
                        fun check() {
                            hasClipboardText =
                                try {
                                    clipboard?.hasPrimaryClip() == true &&
                                        clipboard.primaryClip?.getItemAt(0)?.text?.isNotEmpty() ==
                                            true
                                } catch (_: SecurityException) {
                                    false
                                }
                        }
                        check()
                        val listener = ClipboardManager.OnPrimaryClipChangedListener { check() }
                        clipboard?.addPrimaryClipChangedListener(listener)
                        try {
                            awaitCancellation()
                        } finally {
                            clipboard?.removePrimaryClipChangedListener(listener)
                        }
                    }


                    fun dispatchAccessoryAction(action: AccessoryAction) {
                        if (
                            action is AccessoryAction.SendText && abysshKeys.handleText(action.text)
                        ) {
                            return
                        }
                        if (abysshKeys.isPrefixActive) {
                            abysshKeys.reset()
                        }
                        val currentModifierState = modifierState
                        val result =
                            TerminalAccessoryDispatcher.dispatch(action, currentModifierState)
                        modifierState = result.modifierState

                        if (result.suppressImeInput) {
                            inputViewRef.value?.armInputSuppression(action.toString())
                        }

                        result.specialKey?.let { key ->
                            vm.onSpecialKeyInput(key, currentModifierState.terminalMods())
                        }

                        result.text?.let { text -> vm.onTextInput(text) }

                        if (result.shouldPaste) {
                            pasteClipboard()
                        }
                    }

                    fun copySelection() {
                        val text = selectionState?.text ?: return
                        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal selection", text))
                        Toast.makeText(context, resources.getString(R.string.terminal_copied_selection), Toast.LENGTH_SHORT).show()
                        selection = null
                        selectionState = null
                    }

                    val importFileLauncher =
                        rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetMultipleContents()
                        ) { uris: List<Uri> ->
                            if (uris.isEmpty()) return@rememberLauncherForActivityResult
                            scope.launch(Dispatchers.IO) {
                                var success = 0
                                var failed = 0
                                var lastError: String? = null
                                val total = uris.size
                                uris.forEachIndexed { index, uri ->
                                    val fileName =
                                        context.contentResolver
                                            .query(uri, null, null, null, null)
                                            ?.use { cursor ->
                                                val idx =
                                                    cursor.getColumnIndex(
                                                        OpenableColumns.DISPLAY_NAME
                                                    )
                                                if (cursor.moveToFirst() && idx >= 0) {
                                                    cursor.getString(idx)
                                                } else {
                                                    null
                                                }
                                            } ?: uri.lastPathSegment ?: "uploaded_$index"
                                    val fileSize =
                                        context.contentResolver
                                            .query(uri, null, null, null, null)
                                            ?.use { cursor ->
                                                val idx =
                                                    cursor.getColumnIndex(OpenableColumns.SIZE)
                                                if (cursor.moveToFirst() && idx >= 0) {
                                                    cursor.getLong(idx)
                                                } else {
                                                    0L
                                                }
                                            } ?: 0L
                                    try {
                                        val stream =
                                            context.contentResolver.openInputStream(uri)
                                                ?: throw IllegalStateException("Cannot open file")
                                        stream.use { input ->
                                            vm.beginUpload(fileName)
                                            vm.setUploadProgress(
                                                UploadProgress(
                                                    fileName = fileName,
                                                    bytesWritten = 0,
                                                    totalBytes = fileSize,
                                                    fileIndex = index,
                                                    totalFiles = total,
                                                )
                                            )
                                            val buffer = ByteArray(65536)
                                            var bytesWritten = 0L
                                            var lastProgressBytes = 0L
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                vm.writeUploadChunk(buffer.copyOf(read))
                                                bytesWritten += read
                                                if (
                                                    bytesWritten - lastProgressBytes >= 262144 ||
                                                        bytesWritten == fileSize
                                                ) {
                                                    lastProgressBytes = bytesWritten
                                                    vm.setUploadProgress(
                                                        UploadProgress(
                                                            fileName = fileName,
                                                            bytesWritten = bytesWritten,
                                                            totalBytes = fileSize,
                                                            fileIndex = index,
                                                            totalFiles = total,
                                                        )
                                                    )
                                                }
                                            }
                                            vm.finishUpload()
                                        }
                                        success++
                                    } catch (e: Exception) {
                                        failed++
                                        lastError = e.message ?: e.javaClass.simpleName
                                        runCatching { vm.finishUpload() }
                                    } finally {
                                        vm.setUploadProgress(null)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    val msg =
                                        when {
                                            failed == 0 ->
                                                resources.getQuantityString(R.plurals.terminal_uploaded, success, success)
                                            lastError != null ->
                                                resources.getString(R.string.terminal_uploaded_with_error, success, failed, lastError)
                                            else -> resources.getString(R.string.terminal_uploaded_partial, success, failed)
                                        }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                    var pendingDownloadBytes by remember { mutableStateOf<ByteArray?>(null) }
                    var pendingDownloadName by remember { mutableStateOf("download.bin") }
                    var pendingDeleteEntry by remember {
                        mutableStateOf<io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry?>(
                            null
                        )
                    }

                    val downloadLauncher =
                        rememberLauncherForActivityResult(
                            contract =
                                ActivityResultContracts.CreateDocument("application/octet-stream")
                        ) { uri: Uri? ->
                            val bytes = pendingDownloadBytes
                            if (uri == null || bytes == null) {
                                return@rememberLauncherForActivityResult
                            }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                        context.contentResolver.openOutputStream(uri)?.use {
                                            it.write(bytes)
                                        } ?: error("Cannot open destination")
                                    }
                                    .onSuccess {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                    context,
                                                    resources.getString(R.string.terminal_downloaded, pendingDownloadName),
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                    .onFailure {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                    context,
                                                    resources.getString(R.string.terminal_download_failed, it.message.orEmpty()),
                                                    Toast.LENGTH_LONG,
                                                )
                                                .show()
                                        }
                                    }
                            }
                        }

                    LaunchedEffect(Unit) {
                        requestInputFocus()
                        vm.onFocusChanged(true)
                    }
                    Column(
                        modifier =
                            Modifier.fillMaxSize()
                                .blur(
                                    if (showTabSheet || showGlobalTabManager) {
                                        10.dp
                                    } else {
                                        0.dp
                                    }
                                )
                                .imePadding()
                    ) {
                        // Tab strip (strip mode only — always visible even with zero tabs)
                        if (tabMode == TerminalTabMode.Strip) {
                            TerminalTabStrip(
                                tabs = tabs,
                                activeTabId = activeTabId,
                                onTabSelected = { id ->
                                    vm.selectTab(id)
                                },
                                onAddTab = { showNewTabPicker = true },
                                onOpenManager = {
                                    showGlobalTabManager = true
                                },
                                newTabPicker = newTabPicker,
                            )
                        }

                        // Empty state in strip mode when all tabs are closed
                        if (tabMode == TerminalTabMode.Strip && tabs.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AbyssText(
                                        stringResource(R.string.terminal_no_sessions),
                                        style = typography.body,
                                        color = colors.textMuted,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    AbyssButton(
                                        onClick = openAnotherSessionForCurrentHost,
                                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                        variant = AbyssButtonVariant.Outlined,
                                        bracketed = true,
                                    ) {
                                        AbyssText(
                                            stringResource(R.string.terminal_new_connection),
                                            style = typography.label,
                                            color = colors.accent,
                                        )
                                    }
                                }
                            }
                        } else if (selectedTab == ConnectionTab.Files && filesSupported) {
                            FileBrowserScreen(
                                state = fileBrowserState,
                                onGoUp = vm::goUpDirectory,
                                onRefresh = vm::refreshFileBrowser,
                                onSelectSort = vm::selectFileSort,
                                onOpenPath = vm::openPath,
                                onBackToTerminal = {
                                    vm.selectConnectionTab(ConnectionTab.Terminal)
                                },
                                onCopyPath = { path ->
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("path", path))
                                    Toast.makeText(context, resources.getString(R.string.terminal_copied_path), Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onImportFile = { importFileLauncher.launch("*/*") },
                                onOpenFile = { entry ->
                                    val tabId = activeTabId ?: return@FileBrowserScreen
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                                val bytes = vm.readFile(tabId, entry, 32 * 1024 * 1024)
                                                val safeName = entry.name.ifBlank { "remote_file" }
                                                val outFile = File(context.cacheDir, safeName)
                                                outFile.writeBytes(bytes)
                                                val uri =
                                                    FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        outFile,
                                                    )
                                                val ext =
                                                    safeName.substringAfterLast('.', "").lowercase()
                                                val mime =
                                                    MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(ext)
                                                        ?: "application/octet-stream"
                                                val viewIntent =
                                                    Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                    }
                                                withContext(Dispatchers.Main) {
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            viewIntent,
                                                            resources.getString(R.string.terminal_open_with),
                                                        )
                                                    )
                                                }
                                            }
                                            .onFailure {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                            context,
                                                            resources.getString(R.string.terminal_open_failed, it.message.orEmpty()),
                                                            Toast.LENGTH_LONG,
                                                        )
                                                        .show()
                                                }
                                            }
                                    }
                                },
                                onDeleteFile = { entry -> pendingDeleteEntry = entry },
                                onDownloadFile = { entry ->
                                    val tabId = activeTabId ?: return@FileBrowserScreen
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { vm.readFile(tabId, entry, 16 * 1024 * 1024) }
                                            .onSuccess { bytes ->
                                                pendingDownloadBytes = bytes
                                                pendingDownloadName = entry.name
                                                withContext(Dispatchers.Main) {
                                                    downloadLauncher.launch(entry.name)
                                                }
                                            }
                                            .onFailure {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                            context,
                                                            resources.getString(R.string.terminal_download_failed, it.message.orEmpty()),
                                                            Toast.LENGTH_LONG,
                                                        )
                                                        .show()
                                                }
                                            }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            pendingDeleteEntry?.let { entry ->
                                AbyssDialog(
                                    title = stringResource(R.string.files_delete_title, entry.name),
                                    confirmLabel = stringResource(R.string.action_delete),
                                    dismissLabel = stringResource(R.string.action_cancel),
                                    onConfirm = {
                                        val tabId = activeTabId ?: run {
                                            pendingDeleteEntry = null
                                            return@AbyssDialog
                                        }
                                        pendingDeleteEntry = null
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { vm.deleteFile(tabId, entry) }
                                                .onSuccess {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                context,
                                                                resources.getString(R.string.files_deleted, entry.name),
                                                                Toast.LENGTH_SHORT,
                                                            )
                                                            .show()
                                                    }
                                                }
                                                .onFailure {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                context,
                                                                resources.getString(R.string.files_delete_failed, it.message.orEmpty()),
                                                                Toast.LENGTH_LONG,
                                                            )
                                                            .show()
                                                    }
                                                }
                                        }
                                    },
                                    onDismiss = { pendingDeleteEntry = null },
                                ) {
                                    AbyssText(
                                        stringResource(R.string.files_delete_note),
                                        style = typography.body,
                                    )
                                }
                            }
                        } else {
                            searchState?.let { search ->
                                TerminalSearchBar(
                                    state = search,
                                    onQueryChange = vm::setSearchQuery,
                                    onOlder = vm::searchOlder,
                                    onNewer = vm::searchNewer,
                                    onClose = {
                                        vm.closeSearch()
                                        requestInputFocus()
                                    },
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TerminalCanvas(
                                    snapshot = snapshot,
                                    fontSizeSp = terminalFontSizeSp,
                                    minFontSizeSp = SettingsRepository.MIN_TERMINAL_FONT_SIZE,
                                    maxFontSizeSp = SettingsRepository.MAX_TERMINAL_FONT_SIZE,
                                    cursorColor =
                                        ghosttyTheme?.cursorColor
                                            ?: Color.White.copy(alpha = 0.28f),
                                    cursorTextColor = ghosttyTheme?.cursorText,
                                    selectionBackgroundColor =
                                        ghosttyTheme?.selectionBackground
                                            ?: colors.accent.copy(alpha = 0.45f),
                                    selectionForegroundColor =
                                        ghosttyTheme?.selectionForeground ?: colors.onAccent,
                                    selection = selection,
                                    onSelectionChange = { selection = it },
                                    terminalHandle = sessionState.handle,
                                    modifier = Modifier.fillMaxSize(),
                                    onResize = vm::onCanvasSizeChanged,
                                    onTap = requestInputFocus,
                                    onPrimaryClick = vm::onPrimaryMouseClick,
                                    onLinkTap = vm::onLinkTap,
                                    onSecondaryClick = vm::onSecondaryMouseClick,
                                    onMouseButton = vm::onMouseButton,
                                    onAppSelectionDrag = vm::onAppSelectionDrag,
                                    shiftActive = modifierState.shift,
                                    // The sticky Shift was spent on the selection, not on the next key.
                                    onShiftSelectionStart = {
                                        if (modifierState.shift) modifierState = modifierState.copy(shift = false)
                                    },
                                    onScroll = vm::onScroll,
                                    onFontSizeChange = { sizeSp -> terminalFontSizeSp = sizeSp },
                                    onSelectionChanged = { state -> selectionState = state },
                                    searchMatches = searchState?.matches.orEmpty(),
                                    currentSearchMatch = searchState?.currentMatch,
                                )

                                tappedLink?.let { url ->
                                    TerminalLinkBar(
                                        url = url,
                                        onOpen = {
                                            vm.dismissLink()
                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(context, resources.getString(R.string.terminal_link_no_app), Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCopy = {
                                            vm.dismissLink()
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("link", url))
                                            Toast.makeText(context, resources.getString(R.string.terminal_copied_link), Toast.LENGTH_SHORT).show()
                                        },
                                        onDismiss = vm::dismissLink,
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    )
                                }

                                Row(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isReconnecting) {
                                        AbyssText(
                                            text =
                                                sessionState.reconnectAttempt.takeIf { it > 0 }
                                                    ?.let { stringResource(R.string.terminal_reconnecting_attempt, it) }
                                                    ?: stringResource(R.string.terminal_reconnecting),
                                            style = typography.labelSmall,
                                            color = colors.error,
                                        )
                                    }
                                    if (multiplexerState.duplicateLoading) {
                                        AbyssText(
                                            text = stringResource(R.string.terminal_preparing_multiplexer),
                                            style = typography.labelSmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                    if (pwdText != null) {
                                        AbyssText(
                                            text = pwdText,
                                            style = typography.labelSmall,
                                            color = colors.textPrimary.copy(alpha = 0.7f),
                                        )
                                    }
                                }

                                val selState = selectionState
                                if (selState != null) {
                                    val sel = selection
                                    val isAnchorStart = sel != null && sel.anchorIndex <= sel.focusIndex
                                    TerminalSelectionHandle(
                                        tipX = selState.startOffset.x,
                                        tipY = selState.startOffset.y + selState.cellHeightPx / 2f,
                                        color = colors.accent,
                                        borderColor = colors.background,
                                        cellWidthPx = selState.cellWidthPx,
                                        cellHeightPx = selState.cellHeightPx,
                                        cols = selState.cols,
                                        startCellProvider = {
                                            val s = selection
                                            if (s != null) minOf(s.anchorIndex, s.focusIndex) else 0
                                        },
                                        onDragToCell = { newCell ->
                                            selection = selection?.withStart(newCell, updateAnchor = isAnchorStart)
                                        },
                                    )
                                    TerminalSelectionHandle(
                                        tipX = selState.endOffset.x,
                                        tipY = selState.endOffset.y - selState.cellHeightPx / 2f,
                                        color = colors.accent,
                                        borderColor = colors.background,
                                        cellWidthPx = selState.cellWidthPx,
                                        cellHeightPx = selState.cellHeightPx,
                                        cols = selState.cols,
                                        startCellProvider = {
                                            val s = selection
                                            if (s != null) maxOf(s.anchorIndex, s.focusIndex) else 0
                                        },
                                        onDragToCell = { newCell ->
                                            selection = selection?.withEnd(newCell, updateAnchor = !isAnchorStart)
                                        },
                                    )

                                    val menuGapPx = with(density) { 8.dp.toPx() }
                                    val selLeft = selState.boundsLeft
                                    val selRight = selState.boundsRight
                                    val selTop = selState.boundsTop
                                    val selBottom = selState.boundsBottom
                                    val centerX = (selLeft + selRight) / 2f
                                    val menuWidth = menuSize.width.coerceAtLeast(1)
                                    val menuHeight = menuSize.height.coerceAtLeast(1)
                                    val maxMenuX = (selState.canvasWidthPx - menuWidth).coerceAtLeast(0)
                                    val menuX = (centerX - menuWidth / 2f).toInt().coerceIn(0, maxMenuX)
                                    val aboveY = (selTop - menuHeight - menuGapPx).toInt()
                                    val belowY = (selBottom + menuGapPx).toInt()
                                    val maxMenuY = (selState.canvasHeightPx - menuHeight).coerceAtLeast(0)
                                    val menuY = if (aboveY >= 0) aboveY else belowY.coerceIn(0, maxMenuY)
                                    Row(
                                        modifier =
                                            Modifier.offset { IntOffset(menuX, menuY) }
                                                .onGloballyPositioned { menuSize = it.size }
                                                .background(colors.background)
                                                .border(1.dp, colors.border)
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (!selState.text.isNullOrEmpty()) {
                                            AbyssButton(
                                                onClick = ::copySelection,
                                                variant = AbyssButtonVariant.Ghost,
                                                bracketed = true,
                                                borderColor = colors.textMuted,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 12.dp,
                                                        vertical = 6.dp,
                                                    ),
                                            ) {
                                                AbyssText(
                                                    stringResource(R.string.action_copy),
                                                    style = typography.label,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                        if (hasClipboardText) {
                                            AbyssButton(
                                                onClick = { pasteClipboard() },
                                                variant = AbyssButtonVariant.Ghost,
                                                bracketed = true,
                                                borderColor = colors.textMuted,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 12.dp,
                                                        vertical = 6.dp,
                                                    ),
                                            ) {
                                                AbyssText(
                                                    stringResource(R.string.action_paste),
                                                    style = typography.label,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                    }
                                }

                                AndroidView(
                                    modifier =
                                        Modifier.align(Alignment.BottomStart).size(1.dp).alpha(0f),
                                    factory = { viewContext ->
                                        TerminalInputView(viewContext)
                                            .apply {
                                                onTerminalText = { text ->
                                                    if (abysshKeys.handleText(text)) {
                                                        // Consumed by the abyssh key prefix.
                                                    } else if (
                                                        isPasteShortcut(text, modifierState, ctrlVPastes)
                                                    ) {
                                                        pasteClipboard(applyModifiers = false)
                                                    } else {
                                                        vm.dispatchTextWithModifierState(
                                                            text,
                                                            modifierState,
                                                        )
                                                    }
                                                }
                                                onTerminalKey = { key, codepoint, mods, action, charCode ->
                                                    var shouldForwardToTerminal = true
                                                    val overlayOpen = showTabSheet || showGlobalTabManager
                                                    val overlayTabs = if (showGlobalTabManager) tabs else tabsForHost
                                                    if (
                                                        overlayOpen &&
                                                            overlayTabs.isEmpty() &&
                                                            action == GhosttyKeyAction.Press &&
                                                            key == TerminalSpecialKey.Escape.engineKey
                                                    ) {
                                                        showTabSheet = false
                                                        showGlobalTabManager = false
                                                        shouldForwardToTerminal = false
                                                    } else if (overlayOpen && overlayTabs.isNotEmpty()) {
                                                        var consumedByTabSwitcher = true
                                                        val isPress =
                                                            action == GhosttyKeyAction.Press
                                                        if (isPress && abysshKeys.isPrefixActive) {
                                                            when (
                                                                codepoint.toChar().lowercaseChar()
                                                            ) {
                                                                'n' -> {
                                                                    vm.duplicateActiveTab()
                                                                    vm.selectConnectionTab(
                                                                        ConnectionTab.Terminal
                                                                    )
                                                                    showTabSheet = false
                                                                    showGlobalTabManager = false
                                                                }
                                                                't' -> {
                                                                    if (tabMode == TerminalTabMode.Strip) {
                                                                        showGlobalTabManager = true
                                                                    } else {
                                                                        showTabSheet = true
                                                                    }
                                                                }
                                                                else -> {}
                                                            }
                                                            abysshKeys.reset()
                                                            shouldForwardToTerminal = false
                                                            consumedByTabSwitcher = true
                                                        }
                                                        if (isPress) {
                                                            when (key) {
                                                                TerminalSpecialKey.Left.engineKey,
                                                                TerminalSpecialKey.Up.engineKey ->
                                                                    focusedTabIndex =
                                                                        (focusedTabIndex - 1).mod(
                                                                            overlayTabs.size
                                                                        )

                                                                TerminalSpecialKey.Right.engineKey,
                                                                TerminalSpecialKey.Down.engineKey ->
                                                                    focusedTabIndex =
                                                                        (focusedTabIndex + 1).mod(
                                                                            overlayTabs.size
                                                                        )

                                                                TerminalSpecialKey.Enter
                                                                    .engineKey -> {
                                                                    overlayTabs
                                                                        .getOrNull(focusedTabIndex)
                                                                        ?.let {
                                                                            vm.selectTab(it.id)
                                                                            showTabSheet = false
                                                                            showGlobalTabManager = false
                                                                        }
                                                                }

                                                                TerminalSpecialKey.Escape
                                                                    .engineKey -> {
                                                                    showTabSheet = false
                                                                    showGlobalTabManager = false
                                                                }

                                                                else ->
                                                                    consumedByTabSwitcher = false
                                                            }
                                                        }
                                                        if (consumedByTabSwitcher) {
                                                            shouldForwardToTerminal = false
                                                        }
                                                    }
                                                    val mergedMods =
                                                        mods or modifierState.terminalMods()
                                                    if (
                                                        shouldForwardToTerminal &&
                                                            isSearchShortcut(key, mergedMods)
                                                    ) {
                                                        if (action == GhosttyKeyAction.Press) {
                                                            vm.openSearch()
                                                        }
                                                    } else if (
                                                        shouldForwardToTerminal &&
                                                            isPasteShortcut(key, mergedMods, ctrlVPastes)
                                                    ) {
                                                        // Swallow repeat and release too, so the
                                                        // shell never sees half of the shortcut.
                                                        if (action == GhosttyKeyAction.Press) {
                                                            pasteClipboard(applyModifiers = false)
                                                        }
                                                    } else if (shouldForwardToTerminal) {
                                                        vm.onHardwareKey(
                                                            key,
                                                            codepoint,
                                                            mergedMods,
                                                            action,
                                                            charCode,
                                                        )
                                                    }
                                                }
                                                setOnFocusChangeListener { _, hasFocus ->
                                                    vm.onFocusChanged(hasFocus)
                                                    if (hasFocus) {
                                                        showKeyboard(inputMethodManager)
                                                    }
                                                }
                                            }
                                            .also { view -> inputViewRef.value = view }
                                    },
                                    update = { view ->
                                        if (inputViewRef.value == null) {
                                            inputViewRef.value = view
                                        }
                                        view.disableAutocorrect = disableAutocorrect
                                    },
                                )

                                if (currentTerminalCustomKeyGroups.isNotEmpty() &&
                                    (showCustomActionsFab || fabFilteredActions != null)
                                ) {
                                    TerminalCustomActionsFab(
                                        groups = currentTerminalCustomKeyGroups,
                                        onActionClick = { action ->
                                            val decoded = decodeCustomActionValue(action.payload)
                                            val rawText =
                                                decoded.text +
                                                    if (
                                                        CustomActionModifier.Enter in
                                                            decoded.modifiers
                                                    ) {
                                                        "\n"
                                                    } else {
                                                        ""
                                                    }
                                            val actionModifierState =
                                                modifierStateForCustomAction(decoded.modifiers)
                                            vm.dispatchTextWithModifierState(
                                                rawText,
                                                actionModifierState,
                                            )
                                            requestInputFocus()
                                        },
                                        modifier =
                                            Modifier.align(Alignment.BottomEnd)
                                                .padding(end = 14.dp, bottom = 12.dp),
                                        filteredActions = fabFilteredActions,
                                        onClearFilter = { fabFilteredActions = null },
                                    )
                                }

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = abysshKeys.isPrefixActive,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                                .background(colors.surface)
                                                .border(1.dp, colors.border)
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AbyssText(
                                            "⌘",
                                            style = typography.label,
                                            color = colors.accent,
                                            modifier = Modifier.width(24.dp),
                                        )
                                        FlowRow(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            abysshKeys.hints().forEach { hint ->
                                                AbyssText(
                                                    "${hint.key}: ${hint.description}",
                                                    style = typography.labelSmall,
                                                    color = colors.textSecondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        if (selectedTab == ConnectionTab.Terminal) {
                            if (activeHostCount > 1 && currentHostName != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    AbyssText(
                                        text = currentHostName,
                                        style = typography.labelSmall,
                                        color = colors.textMuted.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            localShellFilesMessage?.let { message ->
                                AbyssText(
                                    text = message,
                                    style = typography.labelSmall,
                                    color = colors.textSecondary,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            KeyboardAccessoryBar(
                                entries = accessoryLayout,
                                modifierState = modifierState,
                                onAction = ::dispatchAccessoryAction,
                                onSettings = onOpenSettings,
                                onAbysshKey = {
                                    abysshKeys.togglePrefix()
                                    requestInputFocus()
                                },
                                abysshKeyActive = abysshKeys.isPrefixActive,
                                onOpenFiles = {
                                    if (filesSupported) {
                                        vm.selectConnectionTab(ConnectionTab.Files)
                                    } else {
                                        showLocalShellFilesUnsupported()
                                    }
                                },
                                useSingleRow = useSingleRowAccessoryBar,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }

                val uploadProgress = fileBrowserState.uploadProgress
                if (uploadProgress != null) {
                    UploadProgressDialog(progress = uploadProgress)
                }
                if (showTabSheet) {
                    val paletteAccessoryAction: (AccessoryAction) -> Unit = { action ->
                        if (
                            !(action is AccessoryAction.SendText &&
                                abysshKeys.handleText(action.text))
                        ) {
                            if (abysshKeys.isPrefixActive) {
                                abysshKeys.reset()
                            }
                            val preDispatchModifierState = modifierState
                            val result =
                                TerminalAccessoryDispatcher.dispatch(action, preDispatchModifierState)
                            modifierState = result.modifierState

                            // Mirror main-handler IME suppression
                            if (result.suppressImeInput) {
                                inputViewRef.value?.armInputSuppression(action.toString())
                            }

                            when (result.specialKey) {
                                TerminalSpecialKey.Left,
                                TerminalSpecialKey.Up -> {
                                    if (tabsForHost.isNotEmpty()) {
                                        focusedTabIndex =
                                            (focusedTabIndex - 1).mod(tabsForHost.size)
                                    }
                                }
                                TerminalSpecialKey.Right,
                                TerminalSpecialKey.Down -> {
                                    if (tabsForHost.isNotEmpty()) {
                                        focusedTabIndex =
                                            (focusedTabIndex + 1).mod(tabsForHost.size)
                                    }
                                }
                                TerminalSpecialKey.Enter -> {
                                    tabsForHost.getOrNull(focusedTabIndex)?.let {
                                        vm.selectTab(it.id)
                                        showTabSheet = false
                                    }
                                }
                                TerminalSpecialKey.Escape -> {
                                    showTabSheet = false
                                }
                                else -> {
                                    result.specialKey?.let { key ->
                                        vm.onSpecialKeyInput(
                                            key,
                                            preDispatchModifierState.terminalMods(),
                                        )
                                    }
                                    result.text?.let { text ->
                                        if (!abysshKeys.handleText(text)) {
                                            vm.onTextInput(text)
                                        }
                                    }
                                }
                            }

                            // Preserve sticky modifiers: paste applies active modifiers
                            // but does not clear them.
                            if (result.shouldPaste) {
                                pasteClipboard()
                            }
                        }
                    }
                    CommandPalette(
                        tabs = tabsForHost,
                        activeTabId = activeTabId,
                        focusedTabIndex = focusedTabIndex,
                        onFocusedTabIndexChange = { focusedTabIndex = it },
                        accessoryEntries = accessoryLayout,
                        accessoryModifierState = modifierState,
                        onAccessoryAction = paletteAccessoryAction,
                        onAbysshKey = { abysshKeys.togglePrefix() },
                        abysshKeyActive = abysshKeys.isPrefixActive,
                        onOpenFiles = {
                            if (filesSupported) {
                                vm.selectConnectionTab(ConnectionTab.Files)
                                showTabSheet = false
                            } else {
                                showLocalShellFilesUnsupported()
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        onSearch = {
                            vm.selectConnectionTab(ConnectionTab.Terminal)
                            vm.openSearch()
                            showTabSheet = false
                        },
                        useSingleRowAccessoryBar = useSingleRowAccessoryBar,
                        onSelectTab = {
                            vm.selectTab(it)
                            showTabSheet = false
                        },
                        onCloseTab = vm::closeTab,
                        onAddTab = {
                            vm.duplicateActiveTab()
                            showTabSheet = false
                        },
                        onDismiss = { showTabSheet = false },
                        multiplexerEnabled = activeTab?.spec?.usesRuntimeMultiplexer == true,
                        multiplexerSessions = multiplexerState.sessions,
                        multiplexerSessionsLoading = multiplexerState.sessionsLoading,
                        multiplexerSessionsError = multiplexerState.sessionsError,
                        onMultiplexerRefresh = vm::listMultiplexerSessionsForCurrentHost,
                        onMultiplexerNew = vm::createNextMultiplexerSession,
                        onMultiplexerAttach = { name ->
                            vm.switchToMultiplexerSession(name, multiplexerState.sessionsSourceTabId)
                        },
                    )
                }

            } else {
                if (tabMode == TerminalTabMode.Strip) {
                    Column(modifier = screenInsetsModifier.fillMaxSize()) {
                        TerminalTabStrip(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            onTabSelected = { id -> vm.selectTab(id) },
                            onAddTab = { showNewTabPicker = true },
                            onOpenManager = { showGlobalTabManager = true },
                            newTabPicker = newTabPicker,
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val message =
                                if (isReconnecting) {
                                    activeTab?.spec?.tabLabel
                                        ?.let { stringResource(R.string.terminal_reconnecting_to, it) }
                                        ?: stringResource(R.string.terminal_reconnecting_ellipsis)
                                } else {
                                    stringResource(R.string.terminal_preparing)
                                }
                            AbyssText(message, style = typography.body)
                        }
                    }
                } else {
                    Column(
                        modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val message =
                            if (isReconnecting) {
                                activeTabForHost?.spec?.host
                                    ?.let { stringResource(R.string.terminal_reconnecting_to, it) }
                                    ?: stringResource(R.string.terminal_reconnecting_ellipsis)
                            } else {
                                stringResource(R.string.terminal_preparing)
                            }
                        AbyssText(message, style = typography.body)
                    }
                }
            }
        }
    }

    BackHandler(enabled = showGlobalTabManager) { showGlobalTabManager = false }

    // Strip mode overlays — hoisted outside the when block so they are
    // available from disconnected, error, connecting, and connected states.
    if (tabMode == TerminalTabMode.Strip) {
        TerminalTabManager(
            visible = showGlobalTabManager,
            tabs = tabs,
            activeTabId = activeTabId,
            focusedTabIndex = focusedTabIndex,
            onFocusedTabIndexChange = { focusedTabIndex = it },
            onSelectTab = { id ->
                vm.selectTab(id)
                showGlobalTabManager = false
            },
            onCloseTab = { id ->
                vm.closeTab(id)
            },
            onDuplicateTab = { id ->
                vm.duplicateTab(id)
            },
            onAddTab = openAnotherSessionForCurrentHost,
            onDismiss = { showGlobalTabManager = false },
            multiplexerEnabled = activeTab?.spec?.usesRuntimeMultiplexer == true,
            multiplexerSessions = multiplexerState.sessions,
            multiplexerSessionsLoading = multiplexerState.sessionsLoading,
            multiplexerSessionsError = multiplexerState.sessionsError,
            onMultiplexerRefresh = vm::listMultiplexerSessionsForCurrentHost,
            onMultiplexerNew = vm::createNextMultiplexerSession,
            onMultiplexerAttach = { name ->
                vm.switchToMultiplexerSession(name, multiplexerState.sessionsSourceTabId)
            },
        )
    }
}

@Composable
private fun TerminalRecoveryActions(
    message: String,
    isMultiplexerPreflight: Boolean,
    dismissMultiplexerLabel: String = stringResource(R.string.action_back),
    onRetryMultiplexer: () -> Unit,
    onConnectWithoutMultiplexer: () -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AbyssText(message, color = colors.error, style = typography.body)
        Spacer(modifier = Modifier.height(16.dp))
        if (isMultiplexerPreflight) {
            AbyssButton(
                onClick = onRetryMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Filled,
            ) {
                AbyssText(stringResource(R.string.terminal_retry_multiplexer), style = typography.label, color = colors.onAccent)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AbyssButton(
                onClick = onConnectWithoutMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Outlined,
                bracketed = true,
            ) {
                AbyssText(stringResource(R.string.terminal_connect_without_multiplexer), style = typography.label)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AbyssButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
            ) {
                AbyssText(dismissMultiplexerLabel, style = typography.label, color = colors.textMuted)
            }
        } else {
            AbyssButton(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Filled,
            ) {
                AbyssText(stringResource(R.string.action_retry), style = typography.label, color = colors.onAccent)
            }
        }
    }
}

@Composable
private fun UploadProgressDialog(progress: UploadProgress) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val barWidthDp = 240.dp
    val barHeightDp = 12.dp
    val filledWidth =
        if (progress.totalBytes > 0) {
            barWidthDp * progress.percent.coerceAtMost(100) / 100f
        } else {
            // Unknown total: show a thin sliver as indeterminate indicator
            barWidthDp * 0.15f
        }

    Box(
        modifier =
            Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.7f)).clickable(
                enabled = false
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.background(colors.surfaceVariant)
                    .border(1.dp, colors.border)
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AbyssText(
                "\u2500\u2500\u25B2\u2500\u2500",
                style = typography.title,
                color = colors.accent,
            )

            AbyssText(text = progress.fileName, style = typography.body, color = colors.textPrimary)

            if (progress.totalFiles > 1) {
                AbyssText(
                    text = stringResource(R.string.terminal_upload_file_index, progress.fileIndex + 1, progress.totalFiles),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            Box(
                modifier =
                    Modifier.width(barWidthDp)
                        .height(barHeightDp)
                        .background(colors.surface)
                        .border(1.dp, colors.border),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier.width(filledWidth).fillMaxHeight().background(colors.accent)
                )
            }

            AbyssText(
                text =
                    if (progress.totalBytes > 0) {
                        val written = formatFileSize(progress.bytesWritten)
                        val total = formatFileSize(progress.totalBytes)
                        "$written / $total  ${progress.percent}%"
                    } else {
                        formatFileSize(progress.bytesWritten)
                    },
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
