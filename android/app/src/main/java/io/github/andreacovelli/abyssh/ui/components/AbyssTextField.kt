package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun AbyssTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
    ),
    autoFocus: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = typography.body.copy(textAlign = textAlign),
        label = if (showLabel && label.isNotEmpty()) {
            { Text(label) }
        } else {
            null
        },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder, style = typography.body, color = colors.textMuted) }
        } else {
            null
        },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.border,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textSecondary,
            cursorColor = colors.accent,
        ),
    )
}
