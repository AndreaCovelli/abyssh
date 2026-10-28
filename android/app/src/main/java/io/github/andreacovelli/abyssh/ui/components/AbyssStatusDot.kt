package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

/** Round status indicator: filled with [color] when [active], an outline ring otherwise. */
@Composable
fun AbyssStatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AbyssColors.current.success,
    size: Dp = 10.dp,
) {
    val ring = AbyssColors.current.textMuted
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (active) {
                    Modifier.background(color)
                } else {
                    Modifier.border(1.5.dp, ring, CircleShape)
                },
            ),
    )
}
