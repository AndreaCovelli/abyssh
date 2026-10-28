package io.github.andreacovelli.abyssh.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

/**
 * Material 3 icon button with a 48 dp touch target. [tonal] adds a filled
 * container behind the icon, for standalone actions such as a floating toggle.
 * [buttonSize] shrinks the button and its touch target, for dense bars only.
 */
@Composable
fun AbyssIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = AbyssColors.current.textSecondary,
    iconSize: Dp = 24.dp,
    tonal: Boolean = false,
    buttonSize: Dp? = null,
    testTag: String? = null,
) {
    val buttonModifier = modifier
        .then(if (buttonSize != null) Modifier.size(buttonSize) else Modifier)
        .then(if (testTag != null) Modifier.semantics { this.testTag = testTag } else Modifier)
    if (buttonSize != null) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButtonContent(icon, contentDescription, onClick, buttonModifier, enabled, tint, iconSize, tonal)
        }
    } else {
        IconButtonContent(icon, contentDescription, onClick, buttonModifier, enabled, tint, iconSize, tonal)
    }
}

@Composable
private fun IconButtonContent(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    tint: Color,
    iconSize: Dp,
    tonal: Boolean,
) {
    val iconContent: @Composable () -> Unit = {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
    if (tonal) {
        val colors = AbyssColors.current
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = colors.surface,
                contentColor = tint,
            ),
            content = iconContent,
        )
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = tint,
                disabledContentColor = AbyssColors.current.disabledText,
            ),
            content = iconContent,
        )
    }
}
