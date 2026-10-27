package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssSegmentedControl
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Suppress("LongMethod")
@Composable
fun AddServerScreen(vm: AddServerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val resources = LocalResources.current
    var showAdditionalSettings by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.errorMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val pendingReconnect by vm.pendingReconnect.collectAsStateWithLifecycle()
    pendingReconnect?.let { pending ->
        val count = pending.tabs.size
        AbyssDialog(
            title = pluralStringResource(R.plurals.reconnect_sessions_title, count, count),
            confirmLabel = stringResource(R.string.reconnect_confirm),
            dismissLabel = stringResource(R.string.reconnect_keep),
            onConfirm = { vm.finishPendingReconnect(reconnect = true) },
            onDismiss = { vm.finishPendingReconnect(reconnect = false) },
        ) {
            AbyssText(
                pluralStringResource(R.plurals.reconnect_sessions_body, count, count),
                style = typography.body,
                color = colors.textMuted,
            )
            AbyssText(
                stringResource(R.string.reconnect_sessions_note),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AbyssText("$ ", style = typography.headline, color = colors.textMuted)
            AbyssText(
                when {
                    form.id != null -> stringResource(R.string.server_edit_title)
                    vm.isCopy -> stringResource(R.string.server_copy_title)
                    else -> stringResource(R.string.server_add_title)
                },
                style = typography.headline,
            )
        }

        SectionHeader(stringResource(R.string.server_section_connection))
        AbyssTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = stringResource(R.string.server_name_label),
            placeholder = stringResource(R.string.server_name_placeholder),
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        AbyssTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = stringResource(R.string.server_host_label),
            placeholder = "192.168.1.10",
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AbyssTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = stringResource(R.string.server_port_label),
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.3f),
            )
            AbyssTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = stringResource(R.string.server_username_label),
                placeholder = "root",
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.7f),
            )
        }

        SectionDivider()

        SectionHeader(stringResource(R.string.server_section_transport))
        AbyssSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH, Transport.Mosh),
            labels =
                mapOf(
                    Transport.SSH to "ssh",
                    Transport.TailscaleSSH to "tailscale",
                    Transport.Mosh to "mosh",
                ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )
        if (form.transport == Transport.TailscaleSSH) {
            AbyssText(
                stringResource(R.string.server_tailscale_hint),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        SectionDivider()

        SectionHeader(stringResource(R.string.server_section_authentication))
        val authOptions =
            when (form.transport) {
                Transport.TailscaleSSH ->
                    listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
                else -> listOf(AuthMethod.Password, AuthMethod.Key)
            }
        if (form.authMethod == AuthMethod.None && form.transport != Transport.TailscaleSSH) {
            androidx.compose.runtime.SideEffect { vm.updateAuthMethod(AuthMethod.Password) }
        }
        val segmentSelected =
            if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
        AbyssSegmentedControl(
            options = authOptions,
            labels =
                mapOf(
                    AuthMethod.Password to stringResource(R.string.auth_password),
                    AuthMethod.Key to stringResource(R.string.auth_ssh_key),
                    AuthMethod.None to stringResource(R.string.auth_none),
                ),
            selected = segmentSelected,
            onSelect = vm::updateAuthMethod,
        )

        when (form.authMethod) {
            AuthMethod.Password -> {
                AbyssTextField(
                    value = form.password,
                    onValueChange = vm::updatePassword,
                    label = stringResource(R.string.server_password_label),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    autoFocus = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AuthMethod.Key,
            AuthMethod.KeyWithPassphrase -> {
                KeyAuthSection(
                    form = form,
                    keys = keys,
                    onGenerate = { vm.generateKey(form.name) },
                    onSelectKey = { vm.selectStoredKey(it) },
                    onCopyPublicKey = {
                        if (form.publicKeyOpenSsh.isBlank()) {
                            Toast.makeText(context, resources.getString(R.string.public_key_unavailable), Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("SSH Public Key", form.publicKeyOpenSsh)
                            )
                            Toast.makeText(context, resources.getString(R.string.public_key_copied), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    AbyssSwitch(
                        checked = form.authMethod == AuthMethod.KeyWithPassphrase,
                        onCheckedChange = { checked ->
                            if (checked) {
                                vm.updateAuthMethod(AuthMethod.KeyWithPassphrase)
                            } else {
                                vm.updateAuthMethod(AuthMethod.Key)
                                vm.updateKeyPassphrase("")
                            }
                        },
                    )
                    AbyssText(stringResource(R.string.key_set_passphrase), style = typography.label)
                }
                if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                    AbyssTextField(
                        value = form.keyPassphrase,
                        onValueChange = vm::updateKeyPassphrase,
                        label = stringResource(R.string.key_passphrase_label),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AuthMethod.None -> {
                AbyssText(
                    stringResource(R.string.auth_none_hint),
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        SectionDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AbyssButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = AbyssButtonVariant.Outlined,
                bracketed = true,
            ) {
                AbyssText(stringResource(R.string.server_additional_settings), style = typography.label)
            }
        }
        if (showAdditionalSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                AbyssText(stringResource(R.string.server_require_biometric), style = typography.label)
                AbyssSwitch(
                    checked = form.requireAuthOnConnect,
                    onCheckedChange = vm::updateRequireAuthOnConnect,
                )
            }
            SectionDivider()
            MultiplexerSection(selected = form.multiplexer, onSelect = vm::updateMultiplexer)
            SectionDivider()
            PostConnectActionSection(
                command = form.postConnectCommand,
                onCommandChange = vm::updatePostConnectCommand,
            )
            SectionDivider()
        }

        val canTest = form.host.isNotBlank() && form.username.isNotBlank()
        AbyssButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = AbyssButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label =
                when (testState.status) {
                    ConnectionTestStatus.Idle -> stringResource(R.string.connection_test)
                    ConnectionTestStatus.Running -> stringResource(R.string.connection_testing)
                    ConnectionTestStatus.Success -> stringResource(R.string.connection_connected)
                    ConnectionTestStatus.Error -> stringResource(R.string.connection_test_failed)
                }
            AbyssText(label, style = typography.label)
        }
        if (testState.status == ConnectionTestStatus.Error && testState.message != null) {
            AbyssText(testState.message ?: "", style = typography.bodySmall, color = colors.error)
        }
        AbyssButton(
            onClick = { vm.save(onBack) },
            enabled = form.canSave(),
            variant = AbyssButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AbyssText(stringResource(R.string.action_save), style = typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AbyssColors.current.border))
}

@Composable
private fun SectionHeader(label: String) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        AbyssText("── ", style = typography.labelSmall, color = colors.textMuted)
        AbyssText(label, style = typography.labelSmall, color = colors.textMuted)
        AbyssText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<io.github.andreacovelli.abyssh.model.SshKey>,
    onGenerate: () -> Unit,
    onSelectKey: (Long?) -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var pickerExpanded by remember { mutableStateOf(false) }

    if (selectedKey != null) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AbyssText("● ", style = typography.body, color = colors.accent)
            AbyssText(selectedKey.name, style = typography.body, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = onCopyPublicKey,
                variant = AbyssButtonVariant.Outlined,
                bracketed = true,
                borderColor = colors.accent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.public_key_copy), style = typography.labelSmall, color = colors.accent)
            }
            AbyssButton(
                onClick = onGenerate,
                variant = AbyssButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.key_new), style = typography.label)
            }
        }
    } else {
        AbyssText(
            stringResource(R.string.key_generate_hint),
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        AbyssButton(
            onClick = onGenerate,
            variant = AbyssButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AbyssText(stringResource(R.string.key_generate), style = typography.label)
        }
    }

    if (keys.isNotEmpty()) {
        AbyssButton(
            onClick = { pickerExpanded = !pickerExpanded },
            variant = AbyssButtonVariant.Ghost,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AbyssText(
                text = stringResource(if (selectedKey != null) R.string.key_change else R.string.key_use_existing),
                style = typography.labelSmall,
                color = colors.accent,
            )
        }
        if (pickerExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    val isSelected = key.id == form.keyId
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onSelectKey(key.id)
                                    pickerExpanded = false
                                }
                                .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        AbyssText(
                            text = (if (isSelected) "● " else "  ") + key.name,
                            style = typography.body,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                        AbyssText(
                            key.algorithm,
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

// The segmented control requires a non-null selection, but "no persistence" is
// modeled as a null MultiplexerType, so wrap the choices in a UI-only enum.
private enum class MultiplexerOption(val type: MultiplexerType?) {
    Off(null),
    Tmux(MultiplexerType.Tmux),
    Zellij(MultiplexerType.Zellij),
    Zmx(MultiplexerType.Zmx),
}

@Composable
private fun MultiplexerSection(selected: MultiplexerType?, onSelect: (MultiplexerType?) -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    SectionHeader(stringResource(R.string.server_section_persistence))
    val options = MultiplexerOption.entries.toList()
    val selectedOption = options.firstOrNull { it.type == selected } ?: MultiplexerOption.Off
    AbyssSegmentedControl(
        options = options,
        labels =
            mapOf(
                MultiplexerOption.Off to stringResource(R.string.multiplexer_off),
                MultiplexerOption.Tmux to "tmux",
                MultiplexerOption.Zellij to "zellij",
                MultiplexerOption.Zmx to "zmx",
            ),
        selected = selectedOption,
        onSelect = { onSelect(it.type) },
        disabledOptions = setOf(MultiplexerOption.Zellij),
    )
}

@Composable
private fun PostConnectActionSection(command: String, onCommandChange: (String) -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssText(stringResource(R.string.server_auto_run), style = typography.label)
    AbyssTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "",
        placeholder = stringResource(R.string.server_auto_run_placeholder),
        showLabel = false,
        singleLine = true,
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AbyssButton(
            onClick = { onCommandChange("") },
            enabled = command.isNotBlank(),
            variant = AbyssButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AbyssText(stringResource(R.string.action_clear), style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
