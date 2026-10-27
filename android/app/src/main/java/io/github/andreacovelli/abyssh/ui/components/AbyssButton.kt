package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Reusable non-clickable button surface: background, border, shape, press alpha,
 * min-height, and content padding.  Used by [AbyssButton] for clickable buttons
 * and by [RepeatableAccessoryButton][io.github.andreacovelli.abyssh.ui.terminal.RepeatableAccessoryButton]
 * for repeat-touch buttons so both share consistent visual styling.
 */
@Composable
fun AbyssButtonSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
    variant: AbyssButtonVariant = AbyssButtonVariant.Filled,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val shape = RectangleShape

    val background: Color = when {
        !enabled -> colors.disabledSurface
        variant == AbyssButtonVariant.Filled -> colors.accent
        backgroundColor != null -> backgroundColor
        else -> Color.Transparent
    }

    val effectiveBorderColor: Color = borderColor ?: colors.border

    val border: BorderStroke? = when {
        !enabled && variant != AbyssButtonVariant.Ghost -> BorderStroke(1.dp, colors.border)
        variant == AbyssButtonVariant.Outlined -> BorderStroke(1.dp, effectiveBorderColor)
        else -> null
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .defaultMinSize(minHeight = 36.dp)
            .alpha(if (pressed && enabled) 0.7f else 1f)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun AbyssButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: AbyssButtonVariant = AbyssButtonVariant.Filled,
    bracketed: Boolean = false,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    testTag: String? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bracketColor: Color = when {
        !enabled -> colors.disabledText
        variant == AbyssButtonVariant.Filled -> colors.onAccent
        borderColor != null -> borderColor
        else -> colors.accent
    }

    val semanticsModifier = Modifier.semantics {
        if (testTag != null) {
            this.testTag = testTag
        }
        if (contentDescription != null) {
            this.contentDescription = contentDescription
        }
    }

    AbyssButtonSurface(
        modifier = modifier
            .then(semanticsModifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        enabled = enabled,
        pressed = pressed,
        variant = variant,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentPadding = contentPadding,
    ) {
        if (bracketed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AbyssText("[", style = typography.label, color = bracketColor)
                content()
                AbyssText("]", style = typography.label, color = bracketColor)
            }
        } else {
            content()
        }
    }
}
