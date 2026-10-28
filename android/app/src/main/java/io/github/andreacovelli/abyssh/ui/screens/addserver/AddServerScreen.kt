package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.ui.components.rememberLocalNetworkPermissionGate

import io.github.andreacovelli.abyssh.ui.components.asString

import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.Alignment
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
import io.github.andreacovelli.abyssh.model.SshKey
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

@Composable
fun AddServerScreen(vm: AddServerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by vm.form.collectAsStateWithLifecycle()
    val withLocalNetworkPermission = rememberLocalNetworkPermissionGate()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val pendingReconnect by vm.pendingReconnect.collectAsStateWithLifecycle()
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var showAdditionalSettings by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.errorMessage.collect { message ->
            Toast.makeText(context, message.resolve(resources), Toast.LENGTH_SHORT).show()
        }
    }
    pendingReconnect?.let { pending ->
        ReconnectDialog(
            count = pending.tabs.size,
            onFinish = { reconnect -> vm.finishPendingReconnect(reconnect = reconnect) },
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AbyssText(
            when {
                form.id != null -> stringResource(R.string.server_edit_title)
                vm.isCopy -> stringResource(R.string.server_copy_title)
                else -> stringResource(R.string.server_add_title)
            },
            style = typography.headline,
        )

        ConnectionSection(form = form, vm = vm)
        SectionDivider()
        TransportSection(selected = form.transport, onSelect = vm::updateTransport)
        SectionDivider()
        AuthenticationSection(form = form, keys = keys, vm = vm)
        SectionDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AbyssButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = AbyssButtonVariant.Outlined,
            ) {
                AbyssText(stringResource(R.string.server_additional_settings), style = typography.label)
            }
        }
        if (showAdditionalSettings) AdditionalSettingsSection(form = form, vm = vm)

        TestAndSaveButtons(
            form = form,
            testState = testState,
            onTest = { withLocalNetworkPermission(form.host.filterNot { it.isWhitespace() }) { vm.testConnection() } },
            onSave = { vm.save(onBack) },
        )
    }
}

@Composable
private fun ReconnectDialog(count: Int, onFinish: (reconnect: Boolean) -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssDialog(
        title = pluralStringResource(R.plurals.reconnect_sessions_title, count, count),
        confirmLabel = stringResource(R.string.reconnect_confirm),
        dismissLabel = stringResource(R.string.reconnect_keep),
        onConfirm = { onFinish(true) },
        onDismiss = { onFinish(false) },
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

private fun plainKeyboard(type: KeyboardType) = KeyboardOptions(
    keyboardType = type,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

@Composable
private fun ConnectionSection(form: AddServerForm, vm: AddServerViewModel) {
    AbyssSectionHeader(stringResource(R.string.server_section_connection))
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
        monospace = true,
        placeholder = "192.168.1.10",
        singleLine = true,
        keyboardOptions = plainKeyboard(KeyboardType.Uri),
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
            monospace = true,
            singleLine = true,
            keyboardOptions = plainKeyboard(KeyboardType.Number),
            autoFocus = false,
            modifier = Modifier.weight(0.3f),
        )
        AbyssTextField(
            value = form.username,
            onValueChange = vm::updateUsername,
            label = stringResource(R.string.server_username_label),
            monospace = true,
            placeholder = "root",
            singleLine = true,
            keyboardOptions = plainKeyboard(KeyboardType.Ascii),
            autoFocus = false,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
private fun TransportSection(selected: Transport, onSelect: (Transport) -> Unit) {
    AbyssSectionHeader(stringResource(R.string.server_section_transport))
    AbyssSegmentedControl(
        options = listOf(Transport.SSH, Transport.TailscaleSSH, Transport.Mosh),
        labels =
            mapOf(
                Transport.SSH to "SSH",
                Transport.TailscaleSSH to "Tailscale",
                Transport.Mosh to "Mosh",
            ),
        selected = selected,
        onSelect = onSelect,
    )
    if (selected == Transport.TailscaleSSH) {
        AbyssText(
            stringResource(R.string.server_tailscale_hint),
            style = AbyssTypography.current.bodySmall,
            color = AbyssColors.current.textMuted,
        )
    }
}

@Composable
private fun AuthenticationSection(form: AddServerForm, keys: List<SshKey>, vm: AddServerViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val authOptions =
        if (form.transport == Transport.TailscaleSSH) {
            listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
        } else {
            listOf(AuthMethod.Password, AuthMethod.Key)
        }

    AbyssSectionHeader(stringResource(R.string.server_section_authentication))
    AbyssSegmentedControl(
        options = authOptions,
        labels =
            mapOf(
                AuthMethod.Password to stringResource(R.string.auth_password),
                AuthMethod.Key to stringResource(R.string.auth_ssh_key),
                AuthMethod.None to stringResource(R.string.auth_none),
            ),
        selected = if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod,
        onSelect = vm::updateAuthMethod,
    )

    when (form.authMethod) {
        AuthMethod.Password -> SecretField(
            value = form.password,
            onValueChange = vm::updatePassword,
            label = stringResource(R.string.server_password_label),
        )
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> {
            KeyAuthSection(
                form = form,
                keys = keys,
                onGenerate = { vm.generateKey(form.name) },
                onSelectKey = vm::selectStoredKey,
                onCopyPublicKey = {
                    if (form.publicKeyOpenSsh.isBlank()) {
                        Toast.makeText(context, resources.getString(R.string.public_key_unavailable), Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        copyToClipboard(context, "SSH Public Key", form.publicKeyOpenSsh, R.string.public_key_copied)
                    }
                },
            )
            KeyPassphraseFields(form = form, vm = vm)
        }
        AuthMethod.None -> AbyssText(
            stringResource(R.string.auth_none_hint),
            style = AbyssTypography.current.bodySmall,
            color = AbyssColors.current.textMuted,
        )
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    AbyssTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeyPassphraseFields(form: AddServerForm, vm: AddServerViewModel) {
    val hasPassphrase = form.authMethod == AuthMethod.KeyWithPassphrase
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssSwitch(checked = hasPassphrase, onCheckedChange = vm::setKeyPassphraseEnabled)
        AbyssText(stringResource(R.string.key_set_passphrase), style = AbyssTypography.current.label)
    }
    if (hasPassphrase) {
        SecretField(
            value = form.keyPassphrase,
            onValueChange = vm::updateKeyPassphrase,
            label = stringResource(R.string.key_passphrase_label),
        )
    }
}

@Composable
private fun AdditionalSettingsSection(form: AddServerForm, vm: AddServerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(
            stringResource(R.string.server_require_biometric),
            style = AbyssTypography.current.label,
            modifier = Modifier.weight(1f),
        )
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

@Composable
private fun TestAndSaveButtons(
    form: AddServerForm,
    testState: ConnectionTestState,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val typography = AbyssTypography.current
    AbyssButton(
        onClick = onTest,
        enabled = form.host.isNotBlank() && form.username.isNotBlank(),
        variant = AbyssButtonVariant.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label =
            when (testState.status) {
                ConnectionTestStatus.Idle -> R.string.connection_test
                ConnectionTestStatus.Running -> R.string.connection_testing
                ConnectionTestStatus.Success -> R.string.connection_connected
                ConnectionTestStatus.Error -> R.string.connection_test_failed
            }
        AbyssText(stringResource(label), style = typography.label)
    }
    val errorMessage = testState.message
    if (testState.status == ConnectionTestStatus.Error && errorMessage != null) {
        AbyssText(errorMessage.asString(), style = typography.bodySmall, color = AbyssColors.current.error)
    }
    AbyssButton(onClick = onSave, enabled = form.canSave(), modifier = Modifier.fillMaxWidth()) {
        AbyssText(stringResource(R.string.action_save), style = typography.label)
    }
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AbyssColors.current.border))
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<SshKey>,
    onGenerate: () -> Unit,
    onSelectKey: (Long?) -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var pickerExpanded by remember { mutableStateOf(false) }

    if (selectedKey != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            AbyssText(selectedKey.name, style = typography.body, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = onCopyPublicKey,
                variant = AbyssButtonVariant.Outlined,
                borderColor = colors.accent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.public_key_copy), style = typography.labelSmall, color = colors.accent)
            }
            AbyssButton(
                onClick = onGenerate,
                variant = AbyssButtonVariant.Outlined,
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            AbyssText(stringResource(R.string.key_generate), style = typography.label)
        }
    }

    if (keys.isNotEmpty()) {
        AbyssButton(
            onClick = { pickerExpanded = !pickerExpanded },
            variant = AbyssButtonVariant.Ghost,
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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.size(18.dp)) {
                                if (isSelected) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = colors.accent,
                                    )
                                }
                            }
                            AbyssText(
                                text = key.name,
                                style = typography.body,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                            )
                        }
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
    AbyssSectionHeader(stringResource(R.string.server_section_persistence))
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
        monospace = true,
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
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AbyssText(stringResource(R.string.action_clear), style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
