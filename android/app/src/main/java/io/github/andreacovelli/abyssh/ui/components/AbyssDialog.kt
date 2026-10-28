package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun AbyssDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.action_cancel),
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        properties = properties,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
        title = { AbyssText(text = title, style = typography.headline) },
        // Scroll the body so the buttons stay on screen with long content or the keyboard up.
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        },
        dismissButton = {
            AbyssButton(onClick = onDismiss, variant = AbyssButtonVariant.Ghost) {
                AbyssText(dismissLabel, style = typography.label)
            }
        },
        confirmButton = {
            AbyssButton(onClick = onConfirm, variant = AbyssButtonVariant.Ghost) {
                AbyssText(confirmLabel, style = typography.label)
            }
        },
    )
}
