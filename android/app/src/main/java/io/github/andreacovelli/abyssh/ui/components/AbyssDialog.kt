package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun AbyssDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val shape = RectangleShape

    Dialog(
        onDismissRequest = onDismiss,
        properties = properties,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, shape)
                .border(BorderStroke(1.dp, colors.border), shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AbyssText(text = title, style = typography.title)
            // Scroll the body so the buttons stay on screen with long content or the keyboard up.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                AbyssButton(
                    onClick = onDismiss,
                    variant = AbyssButtonVariant.Ghost,
                    bracketed = true,
                    borderColor = colors.textMuted,
                ) {
                    AbyssText(dismissLabel, style = typography.label, color = colors.textMuted)
                }
                AbyssButton(
                    onClick = onConfirm,
                    bracketed = true,
                ) {
                    AbyssText(confirmLabel, style = typography.label, color = colors.onAccent)
                }
            }
        }
    }
}
