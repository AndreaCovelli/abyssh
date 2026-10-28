package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.ui.components.AbyssStatusDot
import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val searchQuery by vm.search.collectAsStateWithLifecycle()
    val connectedHostIds by vm.connectedHostIds.collectAsStateWithLifecycle()
    val openSessions by vm.openSessions.collectAsStateWithLifecycle()
    val withNotificationPermission = rememberNotificationPermissionGate()
    val context = LocalContext.current
    val resources = LocalResources.current

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectLocalShell by remember { mutableStateOf(false) }
    LaunchedEffect(hosts, selectedHostId) {
        if (hosts.none { it.id == selectedHostId }) selectedHostId = null
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
            AbyssSectionHeader(stringResource(R.string.server_list_section_hosts))
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
                items(hosts, key = { it.id }) { host ->
                    val isConnected = host.id in connectedHostIds
                    HostCard(
                        host = host,
                        isConnected = isConnected,
                        isSelected = selectedHostId == host.id,
                        onSelect = { selectedHostId = it },
                        onDelete = {
                            if (isConnected) {
                                val message = resources.getString(R.string.server_list_disconnect_before_delete)
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            } else {
                                vm.deleteServer(host.id)
                                selectedHostId = null
                            }
                        },
                        onEdit = { onEditServer(host.id) },
                        onCopy = { onCopyServer(host.id) },
                        onConnect = { withNotificationPermission { onConnectServer(host) } },
                        onDisconnect = { pendingDisconnectHostId = host.id },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddServer,
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { AbyssText(stringResource(R.string.server_list_add), style = AbyssTypography.current.label) },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        )

        if (pendingDisconnectLocalShell) {
            DisconnectDialog(
                name = stringResource(R.string.server_list_local_shell),
                body = pluralStringResource(
                    R.plurals.server_list_disconnect_local_body,
                    openSessions.localShellTabs,
                    openSessions.localShellTabs,
                ),
                onConfirm = vm::disconnectLocalShell,
                onDismiss = { pendingDisconnectLocalShell = false },
            )
        }
        hosts.firstOrNull { it.id == pendingDisconnectHostId }?.let { host ->
            val sessionCount = openSessions.tabsFor(host.id)
            DisconnectDialog(
                name = host.name,
                body = pluralStringResource(R.plurals.server_list_disconnect_body, sessionCount, sessionCount),
                onConfirm = {
                    selectedHostId = null
                    vm.disconnectHost(host.id)
                },
                onDismiss = { pendingDisconnectHostId = null },
            )
        }
    }
}

/**
 * Returns a runner that asks for the notification permission on Android 13 and later
 * before running its action. The session service needs it to show its notification; the
 * action runs whether or not the user grants it.
 */
@Composable
private fun rememberNotificationPermissionGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending[0]
        pending[0] = null
        action?.invoke()
    }
    return { action ->
        val needsPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending[0] = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
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
    var isSearchVisible by remember { mutableStateOf(false) }

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

@Composable
private fun DisconnectDialog(name: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.server_list_disconnect_title, name),
        confirmLabel = stringResource(R.string.server_list_disconnect_confirm),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        AbyssText(body, style = AbyssTypography.current.body, color = AbyssColors.current.warning)
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
            .height(114.dp)
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

/**
 * A saved host. A long press shows the copy and delete actions above the card; a
 * connected card swipes left to disconnect.
 */
@Composable
private fun HostCard(
    host: HostProfile,
    isConnected: Boolean,
    isSelected: Boolean,
    onSelect: (Long?) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            HostSelectionActions(
                hostId = host.id,
                onCancel = { onSelect(null) },
                onCopy = {
                    onSelect(null)
                    onCopy()
                },
                onDelete = onDelete,
            )
        }
        SwipeToDisconnect(swipeKey = host.id, enabled = isConnected, onDisconnect = onDisconnect) {
            HostCardContent(
                host = host,
                isConnected = isConnected,
                isSelected = isSelected,
                onLongPress = { onSelect(host.id) },
                onEdit = {
                    onSelect(null)
                    onEdit()
                },
                onConnect = {
                    onSelect(null)
                    onConnect()
                },
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun HostSelectionActions(hostId: Long, onCancel: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
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

    Box(modifier = Modifier.fillMaxWidth().height(114.dp)) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                .fillMaxSize()
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
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssCard(
        modifier = Modifier
            .fillMaxSize()
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
                AbyssText(host.name, style = typography.title)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(
                    "${host.username}@${host.host}:${host.port}",
                    style = typography.code,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
