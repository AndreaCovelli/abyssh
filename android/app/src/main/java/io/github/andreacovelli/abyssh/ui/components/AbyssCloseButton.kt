package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

/**
 * Close button for screen and sheet headers.
 */
@Composable
fun AbyssCloseButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = AbyssColors.current.textSecondary,
    iconSize: Dp = 24.dp,
) {
    AbyssIconButton(
        icon = R.drawable.ic_close,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = color,
        iconSize = iconSize,
    )
}
