package io.github.andreacovelli.ctulssh.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import io.github.andreacovelli.ctulssh.ui.theme.CtulTypography

@Composable
fun CtulText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CtulTypography.current.body,
    color: Color = CtulColors.current.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}
