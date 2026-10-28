package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

/**
 * Material 3 button in one of three styles: [AbyssButtonVariant.Filled] for the main
 * action, [AbyssButtonVariant.Outlined] for secondary actions, and
 * [AbyssButtonVariant.Ghost] for text-only actions.
 *
 * [borderColor] tints the outline of an outlined button and the label of a ghost
 * button; [backgroundColor] fills an outlined button or replaces the accent fill.
 */
@Composable
fun AbyssButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: AbyssButtonVariant = AbyssButtonVariant.Filled,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    testTag: String? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val buttonModifier = modifier.semantics {
        if (testTag != null) this.testTag = testTag
        if (contentDescription != null) this.contentDescription = contentDescription
    }
    when (variant) {
        AbyssButtonVariant.Filled -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = backgroundColor ?: colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.disabledSurface.copy(alpha = 0.4f),
                disabledContentColor = colors.disabledText,
            ),
            contentPadding = contentPadding,
        ) { content() }

        AbyssButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = backgroundColor ?: Color.Transparent,
                contentColor = colors.textPrimary,
                disabledContentColor = colors.disabledText,
            ),
            border = BorderStroke(
                1.dp,
                if (enabled) borderColor ?: colors.border else colors.border.copy(alpha = 0.5f),
            ),
            contentPadding = contentPadding,
        ) { content() }

        AbyssButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                containerColor = backgroundColor ?: Color.Transparent,
                contentColor = borderColor ?: colors.accent,
                disabledContentColor = colors.disabledText,
            ),
            contentPadding = contentPadding,
        ) { content() }
    }
}
