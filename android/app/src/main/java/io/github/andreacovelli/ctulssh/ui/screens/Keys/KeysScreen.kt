package io.github.andreacovelli.ctulssh.ui.screens.Keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.ctulssh.ui.components.CtulButton
import io.github.andreacovelli.ctulssh.ui.components.CtulButtonVariant
import io.github.andreacovelli.ctulssh.ui.components.CtulCard
import io.github.andreacovelli.ctulssh.ui.components.CtulDialog
import io.github.andreacovelli.ctulssh.ui.components.CtulText
import io.github.andreacovelli.ctulssh.ui.components.CtulTextField
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import io.github.andreacovelli.ctulssh.ui.theme.CtulTypography

@Composable
fun KeysContent(vm: KeysViewModel, modifier: Modifier = Modifier) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    var renameTarget by remember { mutableStateOf<KeyListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<KeyListItem?>(null) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is KeysEvent.Generated ->
                    Toast.makeText(context, "generated ${event.name}", Toast.LENGTH_SHORT).show()
                is KeysEvent.Imported -> {
                    showImportDialog = false
                    val msg =
                        if (event.publicKeyDerived) {
                            "imported ${event.name}"
                        } else {
                            "imported ${event.name} (public key unavailable for this format)"
                        }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is KeysEvent.ImportFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is KeysEvent.GenerateFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                KeysEvent.Renamed -> {
                    renameTarget = null
                    Toast.makeText(context, "key renamed", Toast.LENGTH_SHORT).show()
                }
                is KeysEvent.RenameFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                KeysEvent.Deleted -> {
                    deleteTarget = null
                    Toast.makeText(context, "key deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CtulButton(
                onClick = { showGenerateDialog = true },
                variant = CtulButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                CtulText("+ generate key", style = typography.label, color = colors.accent)
            }
            CtulButton(
                onClick = { showImportDialog = true },
                variant = CtulButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                CtulText("import key", style = typography.label, color = colors.accent)
            }
        }

        if (state.keys.isEmpty()) {
            CtulText(
                text = "No SSH keys yet. Generate one here, or from a server's key auth section.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        } else {
            state.keys.forEach { item ->
                KeyCard(
                    item = item,
                    onRename = { renameTarget = item },
                    onDelete = { deleteTarget = item },
                )
            }
        }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onConfirm = { name ->
                vm.generate(name)
                showGenerateDialog = false
            },
            onDismiss = { showGenerateDialog = false },
        )
    }

    if (showImportDialog) {
        ImportKeyDialog(
            onConfirm = { name, pem -> vm.import(name, pem) },
            onDismiss = { showImportDialog = false },
        )
    }

    renameTarget?.let { target ->
        RenameKeyDialog(
            currentName = target.key.name,
            onConfirm = { newName -> vm.rename(target.key.id, newName) },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        DeleteKeyDialog(
            item = target,
            onConfirm = { vm.delete(target.key.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun KeyCard(item: KeyListItem, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val key = item.key

    CtulCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtulText(
                    key.name,
                    style = typography.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CtulText(key.algorithm, style = typography.bodySmall, color = colors.textMuted)
            }
            Spacer(modifier = Modifier.height(6.dp))
            CtulText(
                text = key.publicKeyOpenSsh,
                style = typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtulButton(
                    onClick = onRename,
                    variant = CtulButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    CtulText("rename", style = typography.label, color = colors.textSecondary)
                }
                CtulButton(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", key.publicKeyOpenSsh))
                        Toast.makeText(context, "public key copied", Toast.LENGTH_SHORT).show()
                    },
                    variant = CtulButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    CtulText("copy", style = typography.label, color = colors.textSecondary)
                }
                CtulButton(
                    onClick = onDelete,
                    variant = CtulButtonVariant.Ghost,
                    bracketed = true,
                    borderColor = colors.error,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    CtulText("delete", style = typography.label, color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun GenerateKeyDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("android-ed25519") }
    CtulDialog(
        title = "generate key",
        confirmLabel = "generate",
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
    ) {
        CtulTextField(value = name, onValueChange = { name = it }, label = "name", singleLine = true)
    }
}

@Composable
private fun ImportKeyDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val typography = CtulTypography.current
    val colors = CtulColors.current
    val context = LocalContext.current
    var name by remember { mutableStateOf("imported-key") }
    var privateKey by remember { mutableStateOf("") }

    val fileLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri
            ->
            if (uri == null) return@rememberLauncherForActivityResult
            val content =
                runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            it.readText()
                        }
                    }
                    .getOrNull()
            if (content.isNullOrBlank()) {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            } else {
                privateKey = content
            }
        }

    CtulDialog(
        title = "import key",
        confirmLabel = "import",
        onConfirm = { onConfirm(name, privateKey) },
        onDismiss = onDismiss,
    ) {
        Column {
            CtulTextField(
                value = name,
                onValueChange = { name = it },
                label = "name",
                singleLine = true,
                autoFocus = false,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtulText("private key", style = typography.labelSmall, color = colors.textMuted)
                CtulButton(
                    onClick = { fileLauncher.launch(arrayOf("*/*")) },
                    variant = CtulButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    CtulText("choose file", style = typography.labelSmall, color = colors.accent)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            CtulTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = "private key",
                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                singleLine = false,
                maxLines = 8,
                showLabel = false,
                autoFocus = false,
            )
            Spacer(modifier = Modifier.height(6.dp))
            CtulText(
                text =
                    "paste or choose an OpenSSH private key. add a passphrase on the server if it's encrypted.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun RenameKeyDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    CtulDialog(
        title = "rename key",
        confirmLabel = "rename",
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
    ) {
        CtulTextField(value = name, onValueChange = { name = it }, label = "name", singleLine = true)
    }
}

@Composable
private fun DeleteKeyDialog(item: KeyListItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    CtulDialog(
        title = "delete key",
        confirmLabel = "delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column {
            CtulText(
                text = "Delete \"${item.key.name}\"? This can't be undone.",
                style = typography.body,
            )
            if (item.usedByServers > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                CtulText(
                    text =
                        "${item.usedByServers} server${if (item.usedByServers == 1) "" else "s"} " +
                            "using this key will need auth updated before connecting again.",
                    style = typography.bodySmall,
                    color = colors.warning,
                )
            }
        }
    }
}
