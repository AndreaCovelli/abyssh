package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssBottomSheet
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssSheetHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinShortcutStore
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
internal fun AbysshCommandsEditorSheet(
    visible: Boolean,
    builtinShortcuts: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var draft by remember(builtinShortcuts, visible) { mutableStateOf(builtinShortcuts) }

    AbyssBottomSheet(visible = visible, onDismiss = onDismiss) {
        AbyssSheetHeader(
            title = stringResource(R.string.settings_app_commands),
            subtitle = stringResource(R.string.settings_app_commands_sheet_hint),
            onClose = onDismiss,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BuiltinCommand.entries.forEach { command ->
                ShortcutRow(
                    command = command,
                    key = draft[command.id] ?: "",
                    onKeyChange = { draft = draft.withShortcut(command, it) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = { draft = BuiltinShortcutStore.defaults },
                variant = AbyssButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_reset), style = typography.label)
            }

            AbyssButton(
                onClick = { onSave(draft) },
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(stringResource(R.string.action_save), style = typography.label, color = colors.onAccent)
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    command: BuiltinCommand,
    key: String,
    onKeyChange: (String) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(command.label, style = typography.label)
            AbyssText(command.description, style = typography.bodySmall, color = colors.textMuted)
        }
        AbyssTextField(
            value = key,
            onValueChange = onKeyChange,
            label = command.label,
            showLabel = false,
            placeholder = stringResource(R.string.settings_shortcut_key_placeholder),
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.width(64.dp),
        )
    }
}

/**
 * Binds the last character of [typed] to [command], keeping each key bound to at most one
 * command. Keys are lowercased to match runtime key matching, so case variants conflict.
 */
internal fun Map<String, String>.withShortcut(command: BuiltinCommand, typed: String): Map<String, String> {
    val key = typed.takeLast(1).lowercase()
    val updated = toMutableMap()
    if (key.isNotEmpty()) {
        updated.entries
            .filter { it.value == key && it.key != command.id }
            .forEach { updated[it.key] = "" }
    }
    updated[command.id] = key
    return updated
}
