package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Outlined Material 3 text field. A [secret] field hides its text behind dots, with an
 * eye button that shows it, and asks the keyboard not to suggest or learn what is typed.
 * [onSubmit] runs on the keyboard's Done action or a hardware Enter in a single-line field.
 */
@Composable
fun AbyssTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    showLabel: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
    ),
    autoFocus: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    monospace: Boolean = false,
    secret: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val focusRequester = remember { FocusRequester() }
    var revealed by rememberSaveable { mutableStateOf(false) }

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
            .focusRequester(focusRequester)
            // A hidden label still names the field for screen readers.
            .then(if (!showLabel && label.isNotEmpty()) Modifier.semantics { contentDescription = label } else Modifier),
        textStyle = (if (monospace) typography.code else typography.body).copy(textAlign = textAlign),
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
        trailingIcon = if (secret) {
            {
                AbyssIconButton(
                    icon = if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.action_hide_secret else R.string.action_show_secret,
                    ),
                    onClick = { revealed = !revealed },
                )
            }
        } else {
            null
        },
        visualTransformation = when {
            !secret -> visualTransformation
            revealed -> VisualTransformation.None
            else -> PasswordVisualTransformation()
        },
        keyboardOptions = keyboardOptions
            .let { if (secret) it.copy(keyboardType = KeyboardType.Password) else it }
            .let { if (onSubmit != null) it.copy(imeAction = ImeAction.Done) else it },
        keyboardActions = if (onSubmit != null) KeyboardActions(onDone = { onSubmit() }) else keyboardActions,
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
