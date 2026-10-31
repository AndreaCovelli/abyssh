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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.andreacovelli.abyssh.service.terminal.sessionLabel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.service.terminal.SessionStatus
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings

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
    newSession: Boolean = false,
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository.getInstance(context) }
    val settings = rememberTerminalUiSettings(repo)
    val fontSize = rememberTerminalFontSize(repo)
    val route = remember(hostId, openLocalShell) { TerminalRoute(hostId, openLocalShell) }
    val overlay = remember { TerminalOverlayState() }
    val launcher = rememberTerminalTabLauncher(vm)
    val endSession = rememberTerminalEndSession(vm)
    val abysshKeys = rememberAbysshKeys(vm, repo, settings, overlay, onOpenSettings)
    KeepScreenOnEffect(settings.keepScreenAwake)
    KeepFocusOnMouseClickEffect()

    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val session = rememberTerminalSessionView(vm.sessionState)
    val sessionState = session.info
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val multiplexer by vm.multiplexer.state.collectAsStateWithLifecycle()
    val routeTabs = remember(tabs, route) { route.tabsIn(tabs) }
    val stripMode = settings.tabMode == TerminalTabMode.Strip

    // Strip mode shows every host's tabs, so a host route stays while any tab is open.
    val hasTabsToShow = if (stripMode && !route.localShell) tabs.isNotEmpty() else routeTabs.isNotEmpty()
    TerminalRouteEffects(vm, route, launcher, newSession, hasTabsToShow, onBack)
    TerminalSwitcherEffects(vm, overlay, routeTabs, activeTabId, activeTab)
    TerminalNoticeEffects(sessionState.status, sessionState.error, multiplexer.preflightError, vm.notices)

    TerminalPassphraseDialog(launcher, onBack)
    TerminalEndSessionDialog(endSession, tabs)
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
    Box(Modifier.fillMaxSize()) {
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
                onEndSession = endSession::request.takeIf { activeTab != null },
            )
            status == SessionStatus.Connecting -> TerminalConnectingContent(
                label = connectingLabel(multiplexer, stripMode, activeTab, routeTab),
                tabStrip = tabStrip,
                modifier = screenModifier,
            )
            !session.hasSnapshot -> TerminalPreparingContent(
                message = preparingMessage(status == SessionStatus.Reconnecting, stripMode, activeTab, routeTab),
                tabStrip = tabStrip,
                modifier = screenModifier,
            )
            else -> TerminalSessionContent(
                vm = vm,
                sessionState = sessionState,
                snapshot = session.snapshot.also { session.keepCurrent() },
                host = TerminalSessionHost(
                    settings = settings,
                    fontSize = fontSize,
                    overlay = overlay,
                    abysshKeys = abysshKeys,
                    routeTabs = routeTabs,
                    tabStrip = tabStrip,
                    onNewConnection = onNewConnection,
                    onEndSession = endSession::request,
                ),
                modifier = screenModifier,
            )
        }
        TerminalSessionNameBanner(
            tabId = activeTabId,
            label = activeTab?.sessionLabel(tabs),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = if (stripMode) 56.dp else 16.dp),
        )
    }

    BackHandler(enabled = overlay.showTabManager) { overlay.showTabManager = false }
    // Outside the status branches, so the manager opens from every state.
    if (stripMode) TerminalStripTabManager(vm, overlay, tabs, activeTabId, abysshKeys, onNewConnection)
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
    abysshKeys: AbysshKeyBindings,
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
        shortcuts = abysshKeys,
        multiplexerPanel = {
            TerminalMultiplexerPanel(
                vm = vm,
                onDismiss = { overlay.showTabManager = false },
                modifier = Modifier.weight(1f, fill = false),
            )
        },
    )
}

/**
 * Opens the route's tab, or a new one when the route asks for [newSession], and leaves
 * the screen once it has no tabs left to show.
 */
@Composable
private fun TerminalRouteEffects(
    vm: TerminalViewModel,
    route: TerminalRoute,
    launcher: TerminalTabLauncher,
    newSession: Boolean,
    hasTabsToShow: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    // Saved, so recreating the activity doesn't open the new session again.
    var newSessionPending by rememberSaveable(route) { mutableStateOf(newSession) }
    LaunchedEffect(route) {
        launcher.reset()
        val routeHostId = route.hostId
        val opensNew = newSessionPending
        newSessionPending = false
        when {
            route.localShell -> if (vm.selectLocalShellTab() == null) vm.openTab(TerminalRoute.localShellSpec)
            routeHostId != null -> {
                // The host's current tab shows until the new one opens.
                val hasTab = vm.selectTabForHost(routeHostId) != null
                if (hasTab && !opensNew) return@LaunchedEffect
                val prepared = vm.prepareTabOpenForHost(routeHostId) ?: return@LaunchedEffect
                // The navigation graph verified the user before opening this route.
                launcher.open(prepared.spec, requiresVerification = false, leaveOnCancel = !hasTab, newSession = hasTab)
            }
        }
    }
    var hasSeenTabs by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route, hasTabsToShow) {
        if (route.hostId == null && !route.localShell) return@LaunchedEffect
        if (hasTabsToShow) {
            hasSeenTabs = true
        } else if (hasSeenTabs) {
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

/** Toasts a connection that just failed, a multiplexer check that failed, and the repository's [notices]. */
@Composable
private fun TerminalNoticeEffects(status: SessionStatus, error: UiText?, preflightError: UiText?, notices: Flow<UiText>) {
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
    LaunchedEffect(notices) {
        notices.collect { notice -> Toast.makeText(context, notice.resolve(resources), Toast.LENGTH_LONG).show() }
    }
}
