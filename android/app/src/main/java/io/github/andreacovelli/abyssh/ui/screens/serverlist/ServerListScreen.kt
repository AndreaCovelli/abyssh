package io.github.andreacovelli.abyssh.ui.screens.serverlist

import android.Manifest
import android.app.Application
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.components.TuiBadge
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun ServerListScreen(
    hosts: List<HostProfile>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddServer: () -> Unit,
    localShellEnabled: Boolean,
    onOpenLocalShell: () -> Unit,
    onEditServer: (Long) -> Unit,
    onCopyServer: (Long) -> Unit,
    onConnectServer: (Long) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val connectedHostIds by sessionRepo.connectedHostIds.collectAsStateWithLifecycle()
    val openTabs by sessionRepo.tabs.collectAsStateWithLifecycle()
    val hasOpenLocalShell = openTabs.any { it.spec.transport == Transport.LocalShell }

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingConnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingLocalShell by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val hostId = pendingConnectHostId
        val openLocalShell = pendingLocalShell
        pendingConnectHostId = null
        pendingLocalShell = false
        when {
            openLocalShell -> onOpenLocalShell()
            hostId != null -> onConnectServer(hostId)
        }
    }
    LaunchedEffect(hosts, selectedHostId) {
        val selected_id = selectedHostId
        if (selected_id != null && hosts.none { it.id == selected_id }) {
            selectedHostId = null
        }
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    fun openLocalShellWithPermission() {
        if (needsNotificationPermission()) {
            pendingLocalShell = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onOpenLocalShell()
        }
    }

    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AbyssText("$ ", style = typography.headline, color = colors.textMuted)
                    AbyssText("Abyssh", style = typography.headline)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSearchVisible && hosts.isNotEmpty()) {
                        AbyssButton(
                            onClick = { isSearchVisible = true },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AbyssText("filter", style = typography.label, color = colors.textSecondary)
                        }
                    }
                    AbyssButton(
                        onClick = onOpenSettings,
                        variant = AbyssButtonVariant.Outlined,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AbyssText("settings", style = typography.label, color = colors.textSecondary)
                    }
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
                    placeholder = "filter hosts",
                    singleLine = true,
                    showLabel = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader("HOSTS")

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (localShellEnabled) {
                    item(key = "local-shell") {
                        LocalShellCard(
                            isOpen = hasOpenLocalShell,
                            onOpen = { openLocalShellWithPermission() },
                        )
                    }
                }
                if (hosts.isEmpty()) {
                    item(key = "empty") {
                        if (searchQuery.isBlank()) {
                            EmptyState()
                        } else {
                            AbyssText(
                                "no hosts match",
                                style = typography.body,
                                color = colors.textMuted,
                            )
                        }
                    }
                } else {
                    items(hosts, key = { it.id }) { host ->
                        val isConnected = host.id in connectedHostIds
                        HostCard(
                            host = host,
                            isConnected = isConnected,
                            isSelected = selectedHostId == host.id,
                            onLongPress = { selectedHostId = host.id },
                            onCancelSelection = { selectedHostId = null },
                            onDeleteSelection = {
                                val is_selected_connected = host.id in connectedHostIds
                                if (is_selected_connected) {
                                    Toast.makeText(
                                        context,
                                        "Disconnect before deleting this host",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    onDeleteServer(host.id)
                                    selectedHostId = null
                                }
                            },
                            onEdit = {
                                selectedHostId = null
                                onEditServer(host.id)
                            },
                            onCopySelection = {
                                selectedHostId = null
                                onCopyServer(host.id)
                            },
                            onConnect = {
                                selectedHostId = null
                                if (needsNotificationPermission()) {
                                    pendingConnectHostId = host.id
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onConnectServer(host.id)
                                }
                            },
                            onDisconnect = {
                                pendingDisconnectHostId = host.id
                            },
                        )
                    }
                }
            }
        }

        AbyssButton(
            onClick = onAddServer,
            variant = AbyssButtonVariant.Filled,
            bracketed = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .height(44.dp),
        ) {
            AbyssText("+ add server", style = typography.label, color = colors.onAccent)
        }

        val pendingDisconnectHost = hosts.firstOrNull { it.id == pendingDisconnectHostId }
        if (pendingDisconnectHost != null) {
            val sessionCount = openTabs.count { it.spec.hostId == pendingDisconnectHost.id }
            AbyssDialog(
                title = "Disconnect ${pendingDisconnectHost.name}?",
                confirmLabel = "Disconnect",
                dismissLabel = "Cancel",
                onConfirm = {
                    selectedHostId = null
                    openTabs
                        .asSequence()
                        .filter { it.spec.hostId == pendingDisconnectHost.id }
                        .map { it.id }
                        .toList()
                        .forEach(sessionRepo::closeTab)
                    pendingDisconnectHostId = null
                },
                onDismiss = { pendingDisconnectHostId = null },
            ) {
                val sessionLabel = if (sessionCount == 1) "session" else "sessions"
                AbyssText(
                    "This closes $sessionCount $sessionLabel on this host.",
                    style = typography.body,
                    color = colors.warning,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )
        AbyssText(
            "── $label",
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.background(colors.background),
        )
    }
}

@Composable
private fun EmptyState() {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssCard(background = colors.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            AbyssText("# no hosts configured", style = typography.body, color = colors.textMuted)
            Spacer(modifier = Modifier.height(8.dp))
            AbyssText(
                "add your first host to connect quickly.",
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
                    AbyssText(
                        if (isOpen) "● " else "○ ",
                        style = typography.title,
                        color = if (isOpen) colors.success else colors.textMuted,
                    )
                    AbyssText("local shell", style = typography.title)
                }
                TuiBadge(text = "android", color = colors.accentSecondary)
            }
            Spacer(modifier = Modifier.height(2.dp))
            AbyssText(
                "> this device · app sandbox",
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
                    bracketed = true,
                    borderColor = colors.accentSecondary,
                    contentDescription = "Open local shell",
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AbyssText("open", style = typography.label, color = colors.accentSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostProfile,
    isConnected: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 132.dp.toPx() }
    val disconnectThresholdPx = with(density) { 72.dp.toPx() }
    val swipeOffsetX = remember(host.id) { Animatable(0f) }
    val hostLabel = "> ${host.username}@${host.host}:${host.port}"

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            swipeOffsetX.snapTo(0f)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AbyssButton(
                        onClick = onCancelSelection,
                        variant = AbyssButtonVariant.Ghost,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AbyssText("cancel", style = typography.label, color = colors.textSecondary)
                    }
                    AbyssButton(
                        onClick = onCopySelection,
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        testTag = "host_copy_${host.id}",
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AbyssText("copy", style = typography.label, color = colors.accentSecondary)
                    }
                    AbyssButton(
                        onClick = onDeleteSelection,
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AbyssText("delete", style = typography.label, color = colors.error)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(114.dp),
        ) {
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.warning.copy(alpha = 0.82f))
                        .padding(end = 14.dp),
                ) {
                    AbyssText(
                        "[disconnect]",
                        style = typography.label,
                        color = colors.background,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                    .fillMaxSize()
                    .pointerInput(host.id, isConnected) {
                        if (!isConnected) {
                            return@pointerInput
                        }
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (swipeOffsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                                scope.launch { swipeOffsetX.snapTo(next) }
                            },
                            onDragEnd = {
                                if (swipeOffsetX.value <= -disconnectThresholdPx) {
                                    onDisconnect()
                                    scope.launch {
                                        swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                    }
                                } else {
                                    scope.launch {
                                        swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                    }
                                }
                            },
                        )
                    },
            ) {
                AbyssCard(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = onConnect,
                            onLongClick = onLongPress,
                        ),
                    background = colors.surfaceVariant,
                    border = when {
                        isSelected -> colors.warning
                        isConnected -> colors.success
                        else -> colors.border
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AbyssText(
                                    if (isConnected) "● " else "○ ",
                                    style = typography.title,
                                    color = if (isConnected) colors.success else colors.textMuted,
                                )
                                AbyssText(host.name, style = typography.title)
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AbyssText(
                                hostLabel,
                                style = typography.body,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (host.transport == Transport.TailscaleSSH) {
                                TuiBadge(
                                    text = "tailscale",
                                    color = colors.accent,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AbyssButton(
                                onClick = onEdit,
                                variant = AbyssButtonVariant.Ghost,
                                bracketed = true,
                                borderColor = colors.textMuted,
                                testTag = "host_edit_${host.id}",
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                AbyssText("edit", style = typography.label, color = colors.textMuted)
                            }
                            AbyssButton(
                                onClick = {
                                    if (isConnected) onDisconnect() else onConnect()
                                },
                                variant = AbyssButtonVariant.Outlined,
                                bracketed = true,
                                borderColor = if (isConnected) colors.warning else colors.accentSecondary,
                                testTag = "host_connect_${host.id}",
                                contentDescription = if (isConnected) "Disconnect from ${host.name}" else "Connect to ${host.name}",
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                AbyssText(
                                    if (isConnected) "disconnect" else "connect",
                                    style = typography.label,
                                    color = if (isConnected) colors.warning else colors.accentSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
