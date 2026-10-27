package io.github.andreacovelli.ctulssh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import androidx.compose.ui.unit.dp

@Composable
fun CtulCard(
    modifier: Modifier = Modifier,
    background: Color = CtulColors.current.surface,
    border: Color = CtulColors.current.border,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RectangleShape
    Column(
        modifier = modifier
            .background(background, shape)
            .border(BorderStroke(1.dp, border), shape),
        content = content,
    )
}
