package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

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
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AbyssButton(
                onClick = onCurrentHost,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Ghost,
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
                                    style = typography.codeSmall,
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

/**
 * The strip's new-tab menu: another session on the current host, or a first one on
 * another saved host.
 */
@Composable
internal fun TerminalNewTabPicker(
    vm: TerminalViewModel,
    overlay: TerminalOverlayState,
    launcher: TerminalTabLauncher,
    route: TerminalRoute,
) {
    if (!overlay.showNewTabPicker) return
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val currentHostId = activeTab?.spec?.hostId ?: route.hostId
    val currentHostName = activeTab?.spec?.hostLabel()
        ?: hosts.firstOrNull { it.id == currentHostId }?.name
        ?: stringResource(R.string.terminal_current_host)
    val otherHosts = remember(hosts, currentHostId) { hosts.filter { it.id != currentHostId } }
    NewTabHostPicker(
        currentHostName = currentHostName,
        otherHosts = otherHosts,
        onCurrentHost = {
            overlay.showNewTabPicker = false
            launcher.openAnotherSession(currentHostId)
        },
        onHostSelected = { hostId ->
            overlay.showNewTabPicker = false
            launcher.openHost(hostId)
        },
        onDismiss = { overlay.showNewTabPicker = false },
    )
}
