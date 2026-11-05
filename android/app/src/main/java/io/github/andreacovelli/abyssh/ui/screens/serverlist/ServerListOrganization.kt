package io.github.andreacovelli.abyssh.ui.screens.serverlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.HostSort
import io.github.andreacovelli.abyssh.data.repository.SshConfigImportPlan
import io.github.andreacovelli.abyssh.model.QuickConnectTarget
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssExpandIcon
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** The "Hosts" heading, with the sort order and the other ways to add a host. */
@Composable
internal fun HostsSectionBar(
    sort: HostSort,
    onSortChange: (HostSort) -> Unit,
    onQuickConnect: () -> Unit,
    onImportSshConfig: () -> Unit,
) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssSectionHeader(stringResource(R.string.server_list_section_hosts), modifier = Modifier.weight(1f))
        AbyssButton(
            onClick = { onSortChange(if (sort == HostSort.Name) HostSort.LastConnected else HostSort.Name) },
            variant = AbyssButtonVariant.Ghost,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            contentDescription = stringResource(R.string.server_list_sort_description, sortLabel(sort)),
        ) {
            AbyssText(sortLabel(sort), style = typography.label, color = colors.textSecondary)
        }
        Box {
            AbyssIconButton(
                icon = R.drawable.ic_more_vert,
                contentDescription = stringResource(R.string.server_list_more),
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { AbyssText(stringResource(R.string.server_list_quick_connect), style = typography.body) },
                    onClick = {
                        menuOpen = false
                        onQuickConnect()
                    },
                )
                DropdownMenuItem(
                    text = { AbyssText(stringResource(R.string.server_list_import_ssh_config), style = typography.body) },
                    onClick = {
                        menuOpen = false
                        onImportSshConfig()
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: HostSort): String = stringResource(
    when (sort) {
        HostSort.Name -> R.string.server_list_sort_name
        HostSort.LastConnected -> R.string.server_list_sort_last_connected
    },
)

/** The heading of a [section], which collapses and expands it. */
@Composable
internal fun HostSectionHeader(section: HostSection, collapsed: Boolean, onToggle: () -> Unit) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    val title = when (section.kind) {
        HostSectionKind.Favorites -> stringResource(R.string.server_list_section_favorites)
        HostSectionKind.Group -> section.groupName
        HostSectionKind.Ungrouped -> stringResource(R.string.server_list_section_other)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = stringResource(if (collapsed) R.string.action_expand else R.string.action_collapse),
                onClick = onToggle,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(
            title,
            style = typography.label,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AbyssText(section.hosts.size.toString(), style = typography.labelSmall, color = colors.textMuted)
        AbyssExpandIcon(expanded = !collapsed)
    }
}

/** Asks for a `user@host:port` address; the editor then opens with it filled in. */
@Composable
internal fun QuickConnectDialog(onConfirm: (QuickConnectTarget) -> Unit, onDismiss: () -> Unit) {
    val typography = AbyssTypography.current
    var address by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val submit = {
        val target = QuickConnectTarget.parse(address)
        if (target != null) onConfirm(target) else invalid = true
    }
    AbyssDialog(
        title = stringResource(R.string.server_list_quick_connect),
        confirmLabel = stringResource(R.string.action_continue),
        onConfirm = submit,
        onDismiss = onDismiss,
    ) {
        AbyssTextField(
            value = address,
            onValueChange = {
                address = it
                invalid = false
            },
            label = stringResource(R.string.quick_connect_address),
            placeholder = "user@host:22",
            singleLine = true,
            monospace = true,
            onSubmit = submit,
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) {
            AbyssText(
                stringResource(R.string.quick_connect_invalid),
                style = typography.bodySmall,
                color = AbyssColors.current.error,
            )
        } else {
            AbyssText(
                stringResource(R.string.quick_connect_hint),
                style = typography.bodySmall,
                color = AbyssColors.current.textMuted,
            )
        }
    }
}

/** Shows what importing an `ssh_config` file would add and what it leaves out. */
@Composable
internal fun SshConfigImportDialog(plan: SshConfigImportPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    val hasHosts = plan.hosts.isNotEmpty()
    AbyssDialog(
        title = stringResource(R.string.server_list_import_ssh_config),
        confirmLabel = stringResource(if (hasHosts) R.string.action_import else R.string.action_close),
        onConfirm = if (hasHosts) onConfirm else onDismiss,
        onDismiss = onDismiss,
        dismissLabel = if (hasHosts) stringResource(R.string.action_cancel) else null,
    ) {
        if (hasHosts) {
            AbyssText(
                pluralStringResource(R.plurals.ssh_config_import_hosts, plan.hosts.size, plan.hosts.size),
                style = typography.body,
            )
            plan.hosts.forEach { host -> AbyssText(host.address(), style = typography.codeSmall) }
        } else {
            AbyssText(stringResource(R.string.ssh_config_import_no_hosts), style = typography.body)
        }
        if (plan.needsSetup > 0) {
            AbyssText(
                pluralStringResource(R.plurals.ssh_config_import_needs_setup, plan.needsSetup, plan.needsSetup),
                style = typography.bodySmall,
                color = colors.warning,
            )
        }
        if (plan.alreadySaved > 0) {
            AbyssText(
                pluralStringResource(R.plurals.ssh_config_import_already_saved, plan.alreadySaved, plan.alreadySaved),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
        if (plan.skipped.isNotEmpty()) {
            AbyssText(stringResource(R.string.ssh_config_import_skipped), style = typography.bodySmall, color = colors.textMuted)
            plan.skipped.forEach { AbyssText(it, style = typography.codeSmall, color = colors.textMuted) }
        }
        if (plan.ignoredOptions.isNotEmpty()) {
            AbyssText(stringResource(R.string.ssh_config_import_ignored), style = typography.bodySmall, color = colors.textMuted)
            AbyssText(plan.ignoredOptions.joinToString(", "), style = typography.codeSmall, color = colors.textMuted)
        }
    }
}
