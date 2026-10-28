package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.model.UiText

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import io.github.andreacovelli.abyssh.service.terminal.TabSession

/**
 * The terminal for one route: a saved host's tabs, or the local shell's. It opens the
 * route's tab, then shows the session's status or the connected session itself.
 */
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    hostId: Long?,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    openLocalShell: Boolean = false,
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository.getInstance(context) }
    val settings = rememberTerminalUiSettings(repo)
    val fontSize = rememberTerminalFontSize(repo)
    val route = remember(hostId, openLocalShell) { TerminalRoute(hostId, openLocalShell) }
    val overlay = remember { TerminalOverlayState() }
    val launcher = rememberTerminalTabLauncher(vm)
    val abysshKeys = rememberAbysshKeys(vm, repo, settings, overlay, onOpenSettings)
    KeepScreenOnEffect(settings.keepScreenAwake)

    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val multiplexer by vm.multiplexer.state.collectAsStateWithLifecycle()
    val routeTabs = remember(tabs, route) { route.tabsIn(tabs) }
    val stripMode = settings.tabMode == TerminalTabMode.Strip

    TerminalRouteEffects(vm, route, launcher, routeTabs.isNotEmpty(), stripMode, onBack)
    TerminalSwitcherEffects(vm, overlay, routeTabs, activeTabId, activeTab)
    TerminalNoticeEffects(sessionState.status, sessionState.error, multiplexer.preflightError)

    TerminalPassphraseDialog(launcher, onBack)
    hostKeyPrompt?.let { prompt -> HostKeyDialog(prompt, vm::onHostKeyDecision) }

    val onNewConnection = { launcher.openAnotherSession(activeTab?.spec?.hostId ?: route.hostId) }
    val tabStrip: (@Composable () -> Unit)? =
        if (stripMode) {
            { TerminalStripBar(vm, overlay, launcher, route) }
        } else {
            null
        }
    val screenModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    val routeTab = activeTab?.takeIf { route.owns(it.spec) }
    val status = if (multiplexer.preflightLoading) SessionStatus.Connecting else sessionState.status
    val snapshot = sessionState.snapshot
    when {
        status == SessionStatus.Disconnected || status == SessionStatus.Error -> TerminalDisconnectedContent(
            vm = vm,
            error = sessionState.error,
            multiplexer = multiplexer,
            tabStrip = tabStrip,
            showNoSessions = stripMode && tabs.isEmpty(),
            onNewConnection = onNewConnection,
            onBack = onBack,
            modifier = screenModifier,
        )
        status == SessionStatus.Connecting -> TerminalConnectingContent(
            label = connectingLabel(multiplexer, stripMode, activeTab, routeTab),
            tabStrip = tabStrip,
            modifier = screenModifier,
        )
        snapshot == null -> TerminalPreparingContent(
            message = preparingMessage(status == SessionStatus.Reconnecting, stripMode, activeTab, routeTab),
            tabStrip = tabStrip,
            modifier = screenModifier,
        )
        else -> TerminalSessionContent(
            vm = vm,
            sessionState = sessionState,
            snapshot = snapshot,
            host = TerminalSessionHost(
                settings = settings,
                fontSize = fontSize,
                overlay = overlay,
                abysshKeys = abysshKeys,
                routeTabs = routeTabs,
                tabStrip = tabStrip,
                onNewConnection = onNewConnection,
                onOpenSettings = onOpenSettings,
            ),
            modifier = screenModifier,
        )
    }

    BackHandler(enabled = overlay.showTabManager) { overlay.showTabManager = false }
    // Outside the status branches, so the manager opens from every state.
    if (stripMode) TerminalStripTabManager(vm, overlay, tabs, activeTabId, onNewConnection)
}

@Composable
private fun TerminalStripBar(
    vm: TerminalViewModel,
    overlay: TerminalOverlayState,
    launcher: TerminalTabLauncher,
    route: TerminalRoute,
) {
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    TerminalTabStrip(
        tabs = tabs,
        activeTabId = activeTabId,
        onTabSelected = vm::selectTab,
        onAddTab = { overlay.showNewTabPicker = true },
        onOpenManager = { overlay.showTabManager = true },
        newTabPicker = { TerminalNewTabPicker(vm, overlay, launcher, route) },
    )
}

@Composable
private fun TerminalStripTabManager(
    vm: TerminalViewModel,
    overlay: TerminalOverlayState,
    tabs: List<TabSession>,
    activeTabId: String?,
    onAddTab: () -> Unit,
) {
    TerminalTabManager(
        visible = overlay.showTabManager,
        tabs = tabs,
        activeTabId = activeTabId,
        focusedTabIndex = overlay.focusedTabIndex,
        onFocusedTabIndexChange = { overlay.focusedTabIndex = it },
        onSelectTab = { id ->
            vm.selectTab(id)
            overlay.showTabManager = false
        },
        onCloseTab = vm::closeTab,
        onDuplicateTab = { id -> vm.duplicateTab(id) },
        onAddTab = onAddTab,
        onDismiss = { overlay.showTabManager = false },
        multiplexerPanel = {
            TerminalMultiplexerPanel(
                vm = vm,
                onDismiss = { overlay.showTabManager = false },
                modifier = Modifier.weight(1f, fill = false),
            )
        },
    )
}

/** Opens the route's tab, and leaves the screen once the route has no tabs left. */
@Composable
private fun TerminalRouteEffects(
    vm: TerminalViewModel,
    route: TerminalRoute,
    launcher: TerminalTabLauncher,
    hasRouteTabs: Boolean,
    stripMode: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(route) {
        launcher.reset()
        val routeHostId = route.hostId
        when {
            route.localShell -> if (vm.selectLocalShellTab() == null) vm.openTab(TerminalRoute.localShellSpec)
            routeHostId != null && vm.selectTabForHost(routeHostId) == null -> {
                val prepared = vm.prepareTabOpenForHost(routeHostId) ?: return@LaunchedEffect
                // The navigation graph verified the user before opening this route.
                launcher.open(prepared.spec, requiresVerification = false, leaveOnCancel = true)
            }
        }
    }
    // Strip mode keeps a host route open with no tabs; the local shell route still closes.
    var hasSeenRouteTabs by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route, hasRouteTabs, stripMode) {
        val leavesWhenEmpty = route.localShell || (route.hostId != null && !stripMode)
        if (!leavesWhenEmpty) return@LaunchedEffect
        if (hasRouteTabs) {
            hasSeenRouteTabs = true
        } else if (hasSeenRouteTabs) {
            currentOnBack()
        }
    }
}

@Composable
private fun TerminalSwitcherEffects(
    vm: TerminalViewModel,
    overlay: TerminalOverlayState,
    routeTabs: List<TabSession>,
    activeTabId: String?,
    activeTab: TabSession?,
) {
    LaunchedEffect(overlay.showTabSheet, routeTabs, activeTabId) {
        if (overlay.showTabSheet) overlay.syncFocus(routeTabs.indexOfFirst { it.id == activeTabId }, routeTabs.size)
    }
    LaunchedEffect(overlay.isSwitcherOpen, activeTab?.id) {
        if (overlay.isSwitcherOpen && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.multiplexer.listSessionsForActiveHost()
        }
    }
}

/** Toasts a connection that just failed and a multiplexer check that failed. */
@Composable
private fun TerminalNoticeEffects(status: SessionStatus, error: UiText?, preflightError: UiText?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var lastStatus by remember { mutableStateOf<SessionStatus?>(null) }
    LaunchedEffect(status, error) {
        val previous = lastStatus
        lastStatus = status
        if (status == SessionStatus.Error && previous != SessionStatus.Error) {
            val message = error?.resolve(resources) ?: resources.getString(R.string.terminal_connection_failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(preflightError) {
        if (preflightError != null) Toast.makeText(context, preflightError.resolve(resources), Toast.LENGTH_LONG).show()
    }
}
