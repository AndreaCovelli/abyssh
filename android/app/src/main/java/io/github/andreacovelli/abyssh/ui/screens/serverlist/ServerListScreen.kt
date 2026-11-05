package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.ui.components.rememberLocalNetworkPermissionGate

import io.github.andreacovelli.abyssh.ui.components.AbyssStatusDot
import io.github.andreacovelli.abyssh.ui.components.rememberNotificationPermissionGate
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.onSecondaryClick
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.QuickConnectTarget
import io.github.andreacovelli.abyssh.model.needsSetup
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.components.AbyssBadge
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun ServerListScreen(
    vm: ServerListViewModel,
    localShellEnabled: Boolean,
    onAddServer: () -> Unit,
    onOpenLocalShell: () -> Unit,
    onEditServer: (Long) -> Unit,
    onCopyServer: (Long) -> Unit,
    onConnectServer: (HostProfile) -> Unit,
    onNewSession: (HostProfile) -> Unit,
    onOpenSettings: () -> Unit,
    onQuickConnect: (QuickConnectTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val collapsedSections by vm.collapsedSections.collectAsStateWithLifecycle()
    val searchQuery by vm.search.collectAsStateWithLifecycle()
    val connectedHostIds by vm.connectedHostIds.collectAsStateWithLifecycle()
    val openSessions by vm.openSessions.collectAsStateWithLifecycle()
    val withNotificationPermission = rememberNotificationPermissionGate()
    val withLocalNetworkPermission = rememberLocalNetworkPermissionGate()
    val context = LocalContext.current
    val resources = LocalResources.current

    var selectedHostId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDisconnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectLocalShell by remember { mutableStateOf(false) }
    var pendingDeleteHostId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showQuickConnect by rememberSaveable { mutableStateOf(false) }
    val sshConfigPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::readSshConfig)
    }
    LaunchedEffect(hosts, selectedHostId) {
        if (hosts.none { it.id == selectedHostId }) selectedHostId = null
    }
    ServerListMessages(vm)

    val withPermissions = { host: HostProfile, open: (HostProfile) -> Unit ->
        withNotificationPermission { withLocalNetworkPermission(host.host) { open(host) } }
    }
    val colors = AbyssColors.current
    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerListHeader(
                searchQuery = searchQuery,
                canFilter = hosts.isNotEmpty(),
                onSearchChange = vm::updateSearchQuery,
                onOpenSettings = onOpenSettings,
            )
            HostsSectionBar(
                sort = sort,
                onSortChange = vm::setSort,
                onQuickConnect = { showQuickConnect = true },
                onImportSshConfig = { sshConfigPicker.launch(arrayOf("*/*")) },
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (localShellEnabled) {
                    item(key = "local-shell") {
                        LocalShellCard(
                            isOpen = openSessions.hasLocalShell,
                            onOpen = { withNotificationPermission(onOpenLocalShell) },
                            onDisconnect = { pendingDisconnectLocalShell = true },
                        )
                    }
                }
                if (hosts.isEmpty()) {
                    item(key = "empty") { EmptyState(filtered = searchQuery.isNotBlank()) }
                }
                // A filter shows every match, including those in collapsed sections.
                hostSectionItems(
                    sections = sections,
                    collapsed = if (searchQuery.isBlank()) collapsedSections else emptySet(),
                    onToggle = vm::toggleSection,
                ) { host ->
                    val isConnected = host.id in connectedHostIds
                    HostCard(
                        host = host,
                        isConnected = isConnected,
                        sessionCount = openSessions.tabsFor(host.id),
                        isSelected = selectedHostId == host.id,
                        actions = HostCardActions(
                            onSelect = { selectedHostId = it },
                            onDelete = {
                                if (isConnected) {
                                    val message = resources.getString(R.string.server_list_disconnect_before_delete)
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingDeleteHostId = host.id
                                }
                            },
                            onEdit = { onEditServer(host.id) },
                            onCopy = { onCopyServer(host.id) },
                            onToggleFavorite = { vm.setFavorite(host.id, !host.favorite) },
                            onConnect = {
                                if (host.needsSetup) onEditServer(host.id) else withPermissions(host, onConnectServer)
                            },
                            onNewSession = { withPermissions(host, onNewSession) },
                            onDisconnect = { pendingDisconnectHostId = host.id },
                        ),
                    )
                }
            }
        }

        AddServerButton(onAddServer, Modifier.align(Alignment.BottomEnd))

        if (showQuickConnect) {
            QuickConnectDialog(
                onConfirm = { target ->
                    showQuickConnect = false
                    onQuickConnect(target)
                },
                onDismiss = { showQuickConnect = false },
            )
        }
        SshConfigImportPrompt(vm)
        if (pendingDisconnectLocalShell) {
            LocalShellDisconnectDialog(
                tabs = openSessions.localShellTabs,
                onConfirm = vm::disconnectLocalShell,
                onDismiss = { pendingDisconnectLocalShell = false },
            )
        }
        hosts.firstOrNull { it.id == pendingDeleteHostId }?.let { host ->
            DeleteHostDialog(
                name = host.name,
                onConfirm = {
                    vm.deleteServer(host.id)
                    selectedHostId = null
                },
                onDismiss = { pendingDeleteHostId = null },
            )
        }
        hosts.firstOrNull { it.id == pendingDisconnectHostId }?.let { host ->
            HostDisconnectDialog(
                host = host,
                sessions = openSessions.sessionsFor(host.id),
                onEnd = vm::closeSession,
                onEndAll = {
                    selectedHostId = null
                    vm.disconnectHost(host.id)
                },
                onDismiss = { pendingDisconnectHostId = null },
            )
        }
    }
}

@Composable
private fun ServerListMessages(vm: ServerListViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            Toast.makeText(context, message.resolve(resources), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun AddServerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
        text = { AbyssText(stringResource(R.string.server_list_add), style = AbyssTypography.current.label) },
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    )
}

/** Shows what importing the chosen `ssh_config` file would do, once it has been read. */
@Composable
private fun SshConfigImportPrompt(vm: ServerListViewModel) {
    val plan by vm.sshConfigImport.collectAsStateWithLifecycle()
    plan?.let {
        SshConfigImportDialog(
            plan = it,
            onConfirm = vm::confirmSshConfigImport,
            onDismiss = vm::dismissSshConfigImport,
        )
    }
}

@Composable
private fun LocalShellDisconnectDialog(tabs: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DisconnectDialog(
        name = stringResource(R.string.server_list_local_shell),
        body = pluralStringResource(R.plurals.server_list_disconnect_local_body, tabs, tabs),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The [sections] as list items: a heading for each, unless all the hosts are in no group,
 * and a [hostCard] per host of the sections not [collapsed].
 */
private fun LazyListScope.hostSectionItems(
    sections: List<HostSection>,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    hostCard: @Composable (HostProfile) -> Unit,
) {
    sections.forEach { section ->
        val isCollapsed = section.key in collapsed
        if (section.kind != HostSectionKind.Ungrouped || sections.size > 1) {
            item(key = "section-${section.key}") {
                HostSectionHeader(section, isCollapsed, onToggle = { onToggle(section.key) })
            }
        }
        if (!isCollapsed) items(section.hosts, key = { it.id }) { host -> hostCard(host) }
    }
}

@Composable
private fun ServerListHeader(
    searchQuery: String,
    canFilter: Boolean,
    onSearchChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    // Saved, and open whenever a filter is set, so a filter never applies with its field hidden.
    var isSearchVisible by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    // Back, or Esc on a keyboard, clears the filter and closes it.
    BackHandler(enabled = isSearchVisible) {
        onSearchChange("")
        isSearchVisible = false
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(stringResource(R.string.app_name), style = typography.headline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isSearchVisible && canFilter) {
                HeaderButton(stringResource(R.string.server_list_filter)) { isSearchVisible = true }
            }
            HeaderButton(stringResource(R.string.server_list_settings), onOpenSettings)
        }
    }

    if (isSearchVisible) {
        AbyssTextField(
            value = searchQuery,
            onValueChange = { value ->
                onSearchChange(value)
                if (value.isBlank()) isSearchVisible = false
            },
            label = "",
            placeholder = stringResource(R.string.server_list_filter_placeholder),
            singleLine = true,
            showLabel = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    AbyssButton(
        onClick = onClick,
        variant = AbyssButtonVariant.Outlined,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AbyssText(label, style = AbyssTypography.current.label, color = AbyssColors.current.textSecondary)
    }
}

/** Ends every session on a host with one session, or lets the user pick when it has several. */
@Composable
private fun HostDisconnectDialog(
    host: HostProfile,
    sessions: List<OpenSession>,
    onEnd: (String) -> Unit,
    onEndAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Every session closed elsewhere while the dialog was up: nothing is left to end.
    LaunchedEffect(sessions.isEmpty()) {
        if (sessions.isEmpty()) onDismiss()
    }
    when {
        sessions.size > 1 -> HostSessionsDialog(host.name, sessions, onEnd, onEndAll, onDismiss)
        sessions.size == 1 -> DisconnectDialog(
            name = host.name,
            body = pluralStringResource(R.plurals.server_list_disconnect_body, 1, 1),
            onConfirm = onEndAll,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    if (filtered) {
        AbyssText(stringResource(R.string.server_list_no_match), style = typography.body, color = colors.textMuted)
        return
    }
    AbyssCard(background = colors.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            AbyssText(stringResource(R.string.server_list_empty_title), style = typography.body, color = colors.textMuted)
            Spacer(modifier = Modifier.height(8.dp))
            AbyssText(
                stringResource(R.string.server_list_empty_hint),
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalShellCard(
    isOpen: Boolean,
    onOpen: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 114.dp)
            .combinedClickable(onClick = onOpen),
        background = colors.surfaceVariant,
        border = if (isOpen) colors.success else colors.border,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AbyssStatusDot(active = isOpen)
                    Spacer(modifier = Modifier.width(10.dp))
                    AbyssText(stringResource(R.string.server_list_local_shell), style = typography.title)
                }
                AbyssBadge(text = stringResource(R.string.server_list_local_badge), color = colors.accentSecondary)
            }
            Spacer(modifier = Modifier.height(2.dp))
            AbyssText(
                stringResource(R.string.server_list_local_subtitle),
                style = typography.body,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AbyssButton(
                    onClick = onOpen,
                    variant = AbyssButtonVariant.Outlined,
                    borderColor = colors.accentSecondary,
                    contentDescription = stringResource(R.string.server_list_open_local_shell),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AbyssText(stringResource(R.string.action_open), style = typography.label, color = colors.accentSecondary)
                }
                if (isOpen) {
                    AbyssButton(
                        onClick = onDisconnect,
                        variant = AbyssButtonVariant.Outlined,
                        borderColor = colors.warning,
                        contentDescription = stringResource(R.string.server_list_disconnect_local_shell),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        AbyssText(stringResource(R.string.action_disconnect), style = typography.label, color = colors.warning)
                    }
                }
            }
        }
    }
}

private class HostCardActions(
    val onSelect: (Long?) -> Unit,
    val onDelete: () -> Unit,
    val onCopy: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onEdit: () -> Unit,
    val onConnect: () -> Unit,
    val onNewSession: () -> Unit,
    val onDisconnect: () -> Unit,
)

/**
 * A saved host. A long press shows the copy and delete actions above the card; a
 * connected card swipes left to disconnect.
 */
@Composable
private fun HostCard(
    host: HostProfile,
    isConnected: Boolean,
    sessionCount: Int,
    isSelected: Boolean,
    actions: HostCardActions,
) {
    val onSelect = actions.onSelect
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            HostSelectionActions(
                hostId = host.id,
                favorite = host.favorite,
                onCancel = { onSelect(null) },
                onToggleFavorite = {
                    onSelect(null)
                    actions.onToggleFavorite()
                },
                onCopy = {
                    onSelect(null)
                    actions.onCopy()
                },
                onDelete = actions.onDelete,
            )
        }
        SwipeToDisconnect(swipeKey = host.id, enabled = isConnected, onDisconnect = actions.onDisconnect) {
            HostCardContent(
                host = host,
                isConnected = isConnected,
                sessionCount = sessionCount,
                isSelected = isSelected,
                onLongPress = { onSelect(host.id) },
                onEdit = {
                    onSelect(null)
                    actions.onEdit()
                },
                onConnect = {
                    onSelect(null)
                    actions.onConnect()
                },
                onNewSession = {
                    onSelect(null)
                    actions.onNewSession()
                },
                onDisconnect = actions.onDisconnect,
            )
        }
    }
}

@Composable
private fun HostSelectionActions(
    hostId: Long,
    favorite: Boolean,
    onCancel: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssButton(onClick = onCancel, variant = AbyssButtonVariant.Ghost, contentPadding = padding) {
            AbyssText(stringResource(R.string.action_cancel), style = typography.label, color = colors.textSecondary)
        }
        AbyssButton(onClick = onToggleFavorite, variant = AbyssButtonVariant.Outlined, contentPadding = padding) {
            AbyssText(
                stringResource(if (favorite) R.string.server_list_unfavorite else R.string.server_list_favorite),
                style = typography.label,
                color = colors.accentSecondary,
            )
        }
        AbyssButton(
            onClick = onCopy,
            variant = AbyssButtonVariant.Outlined,
            testTag = "host_copy_$hostId",
            contentPadding = padding,
        ) {
            AbyssText(stringResource(R.string.action_copy), style = typography.label, color = colors.accentSecondary)
        }
        AbyssButton(onClick = onDelete, variant = AbyssButtonVariant.Outlined, contentPadding = padding) {
            AbyssText(stringResource(R.string.action_delete), style = typography.label, color = colors.error)
        }
    }
}

/** Lets [content] slide left over a disconnect hint; a long enough swipe calls [onDisconnect]. */
@Composable
private fun SwipeToDisconnect(
    swipeKey: Any,
    enabled: Boolean,
    onDisconnect: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 132.dp.toPx() }
    val disconnectThresholdPx = with(density) { 72.dp.toPx() }
    val swipeOffsetX = remember(swipeKey) { Animatable(0f) }

    LaunchedEffect(enabled) {
        if (!enabled) swipeOffsetX.snapTo(0f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CardDefaults.outlinedShape)
                    .background(colors.warning.copy(alpha = 0.82f))
                    .padding(end = 14.dp),
            ) {
                AbyssText(
                    stringResource(R.string.server_list_swipe_disconnect),
                    style = AbyssTypography.current.label,
                    color = colors.background,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .heightIn(min = 114.dp)
                .pointerInput(swipeKey, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (swipeOffsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            scope.launch { swipeOffsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (swipeOffsetX.value <= -disconnectThresholdPx) onDisconnect()
                            scope.launch { swipeOffsetX.animateTo(0f, animationSpec = tween(140)) }
                        },
                    )
                },
            // The card fills the row's minimum height and grows past it with large text.
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCardContent(
    host: HostProfile,
    isConnected: Boolean,
    sessionCount: Int,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onNewSession: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssCard(
        modifier = Modifier
            .fillMaxSize()
            .onSecondaryClick(onLongPress)
            .combinedClickable(onClick = onConnect, onLongClick = onLongPress),
        background = colors.surfaceVariant,
        border = when {
            isSelected -> colors.warning
            isConnected -> colors.success
            else -> colors.border
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AbyssStatusDot(active = isConnected)
                Spacer(modifier = Modifier.width(10.dp))
                AbyssText(
                    host.name,
                    style = typography.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sessionCount > 0) {
                    AbyssText(
                        pluralStringResource(R.plurals.server_list_session_count, sessionCount, sessionCount),
                        style = typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(
                    host.address(),
                    style = typography.code,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (host.needsSetup) {
                    AbyssBadge(text = stringResource(R.string.server_list_needs_setup), color = colors.warning)
                }
                if (host.transport == Transport.TailscaleSSH) {
                    AbyssBadge(text = "Tailscale", color = colors.accent)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HostCardButtons(
                host = host,
                isConnected = isConnected,
                onEdit = onEdit,
                onConnect = onConnect,
                onNewSession = onNewSession,
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun HostCardButtons(
    host: HostProfile,
    isConnected: Boolean,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onNewSession: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val connectColor = if (isConnected) colors.warning else colors.accentSecondary
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AbyssButton(
            onClick = onEdit,
            variant = AbyssButtonVariant.Ghost,
            borderColor = colors.textMuted,
            testTag = "host_edit_${host.id}",
            contentPadding = padding,
        ) {
            AbyssText(stringResource(R.string.action_edit), style = typography.label, color = colors.textMuted)
        }
        if (isConnected) {
            AbyssButton(
                onClick = onNewSession,
                variant = AbyssButtonVariant.Outlined,
                borderColor = colors.accentSecondary,
                testTag = "host_new_session_${host.id}",
                contentDescription = stringResource(R.string.server_list_new_session_on, host.name),
                contentPadding = padding,
            ) {
                AbyssText(
                    stringResource(R.string.server_list_new_session),
                    style = typography.label,
                    color = colors.accentSecondary,
                )
            }
        }
        AbyssButton(
            onClick = if (isConnected) onDisconnect else onConnect,
            variant = AbyssButtonVariant.Outlined,
            borderColor = connectColor,
            testTag = "host_connect_${host.id}",
            contentDescription = stringResource(
                if (isConnected) R.string.server_list_disconnect_from else R.string.server_list_connect_to,
                host.name,
            ),
            contentPadding = padding,
        ) {
            AbyssText(
                stringResource(if (isConnected) R.string.action_disconnect else R.string.action_connect),
                style = typography.label,
                color = connectColor,
            )
        }
    }
}

/** `user@host:port`, with an IPv6 host in brackets so its colons don't run into the port's. */
internal fun HostProfile.address(): String {
    val shownHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
    return "$username@$shownHost:$port"
}
