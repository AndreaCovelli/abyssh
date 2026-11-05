package io.github.andreacovelli.abyssh.ui.screens.knownhosts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.ssh.KnownHostKey
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** The host keys the user has trusted, each with its SHA-256 fingerprint and a way to forget it. */
@Composable
fun KnownHostsScreen(vm: KnownHostsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var forgetTarget by remember { mutableStateOf<KnownHostKey?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssText(stringResource(R.string.settings_known_hosts), style = typography.headline)
            AbyssCloseButton(onClick = onBack, contentDescription = stringResource(R.string.action_close))
        }
        if (keys.isEmpty()) {
            AbyssText(stringResource(R.string.known_hosts_empty), style = typography.body, color = colors.textMuted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(keys, key = { "${it.host}:${it.port}:${it.algorithm}" }) { key ->
                    KnownHostCard(key, onForget = { forgetTarget = key })
                }
            }
        }
    }

    forgetTarget?.let { target ->
        ForgetKeyDialog(
            key = target,
            onConfirm = {
                vm.forget(target)
                forgetTarget = null
            },
            onDismiss = { forgetTarget = null },
        )
    }
}

@Composable
private fun KnownHostCard(key: KnownHostKey, onForget: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val address = hostAddress(key)
    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            AbyssText(address, style = typography.code)
            Spacer(modifier = Modifier.height(4.dp))
            AbyssText(key.algorithm, style = typography.bodySmall, color = colors.textMuted)
            Spacer(modifier = Modifier.height(6.dp))
            AbyssText(key.fingerprint, style = typography.codeSmall, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            val description = stringResource(R.string.known_hosts_forget_description, address)
            AbyssButton(
                onClick = onForget,
                variant = AbyssButtonVariant.Ghost,
                borderColor = colors.error,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                contentDescription = description,
            ) {
                AbyssText(stringResource(R.string.known_hosts_forget), style = typography.label, color = colors.error)
            }
        }
    }
}

@Composable
private fun ForgetKeyDialog(key: KnownHostKey, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.known_hosts_forget_title),
        confirmLabel = stringResource(R.string.known_hosts_forget),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        AbyssText(
            stringResource(R.string.known_hosts_forget_note, hostAddress(key)),
            style = AbyssTypography.current.body,
        )
    }
}

/** `host:port`, with an IPv6 address in brackets. */
internal fun hostAddress(key: KnownHostKey): String =
    if (':' in key.host) "[${key.host}]:${key.port}" else "${key.host}:${key.port}"
