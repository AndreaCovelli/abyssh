package io.github.andreacovelli.abyssh.ui.screens.keys

import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun KeysContent(vm: KeysViewModel, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()

    var renameTarget by remember { mutableStateOf<KeyListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<KeyListItem?>(null) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is KeysEvent.Generated ->
                    Toast.makeText(context, resources.getString(R.string.keys_generated, event.name), Toast.LENGTH_SHORT).show()
                is KeysEvent.Imported -> {
                    showImportDialog = false
                    val msg =
                        if (event.publicKeyDerived) {
                            resources.getString(R.string.keys_imported, event.name)
                        } else {
                            resources.getString(R.string.keys_imported_without_public, event.name)
                        }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is KeysEvent.ImportFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is KeysEvent.GenerateFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                KeysEvent.Renamed -> {
                    renameTarget = null
                    Toast.makeText(context, resources.getString(R.string.keys_renamed), Toast.LENGTH_SHORT).show()
                }
                is KeysEvent.RenameFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                KeysEvent.Deleted -> {
                    deleteTarget = null
                    Toast.makeText(context, resources.getString(R.string.keys_deleted), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = { showGenerateDialog = true },
                variant = AbyssButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.keys_generate_button), style = typography.label, color = colors.accent)
            }
            AbyssButton(
                onClick = { showImportDialog = true },
                variant = AbyssButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.keys_import_title), style = typography.label, color = colors.accent)
            }
        }

        if (state.keys.isEmpty()) {
            AbyssText(
                text = stringResource(R.string.keys_empty),
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
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val context = LocalContext.current
    val key = item.key

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(
                    key.name,
                    style = typography.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AbyssText(key.algorithm, style = typography.bodySmall, color = colors.textMuted)
            }
            Spacer(modifier = Modifier.height(6.dp))
            AbyssText(
                text = key.publicKeyOpenSsh,
                style = typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AbyssButton(
                    onClick = onRename,
                    variant = AbyssButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AbyssText(stringResource(R.string.action_rename), style = typography.label, color = colors.textSecondary)
                }
                AbyssButton(
                    onClick = {
                        copyToClipboard(context, "SSH Public Key", key.publicKeyOpenSsh, R.string.public_key_copied)
                    },
                    variant = AbyssButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AbyssText(stringResource(R.string.action_copy), style = typography.label, color = colors.textSecondary)
                }
                AbyssButton(
                    onClick = onDelete,
                    variant = AbyssButtonVariant.Ghost,
                    borderColor = colors.error,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AbyssText(stringResource(R.string.action_delete), style = typography.label, color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun GenerateKeyDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("android-ed25519") }
    AbyssDialog(
        title = stringResource(R.string.key_generate),
        confirmLabel = stringResource(R.string.action_generate),
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
    ) {
        AbyssTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.key_details_name), singleLine = true)
    }
}

@Composable
private fun ImportKeyDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var name by remember { mutableStateOf("imported-key") }
    var privateKey by remember { mutableStateOf("") }

    val fileLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val content =
                runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            it.readText()
                        }
                    }
                    .getOrNull()
            if (content.isNullOrBlank()) {
                Toast.makeText(context, resources.getString(R.string.keys_file_read_failed), Toast.LENGTH_SHORT).show()
            } else {
                privateKey = content
            }
        }

    AbyssDialog(
        title = stringResource(R.string.keys_import_title),
        confirmLabel = stringResource(R.string.backup_import),
        onConfirm = { onConfirm(name, privateKey) },
        onDismiss = onDismiss,
    ) {
        Column {
            AbyssTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.key_details_name),
                singleLine = true,
                autoFocus = false,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(stringResource(R.string.keys_private_key), style = typography.labelSmall, color = colors.textMuted)
                AbyssButton(
                    onClick = { fileLauncher.launch(arrayOf("*/*")) },
                    variant = AbyssButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AbyssText(stringResource(R.string.keys_choose_file), style = typography.labelSmall, color = colors.accent)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            AbyssTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = stringResource(R.string.keys_private_key),
                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                singleLine = false,
                maxLines = 8,
                showLabel = false,
                autoFocus = false,
            )
            Spacer(modifier = Modifier.height(6.dp))
            AbyssText(
                text =
                    stringResource(R.string.keys_import_hint),
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
    AbyssDialog(
        title = stringResource(R.string.keys_rename_title),
        confirmLabel = stringResource(R.string.action_rename),
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
    ) {
        AbyssTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.key_details_name), singleLine = true)
    }
}

@Composable
private fun DeleteKeyDialog(item: KeyListItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssDialog(
        title = stringResource(R.string.keys_delete_title),
        confirmLabel = stringResource(R.string.action_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column {
            AbyssText(
                text = stringResource(R.string.keys_delete_confirm, item.key.name),
                style = typography.body,
            )
            if (item.usedByServers > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                AbyssText(
                    text = pluralStringResource(R.plurals.keys_delete_used_by, item.usedByServers, item.usedByServers),
                    style = typography.bodySmall,
                    color = colors.warning,
                )
            }
        }
    }
}
