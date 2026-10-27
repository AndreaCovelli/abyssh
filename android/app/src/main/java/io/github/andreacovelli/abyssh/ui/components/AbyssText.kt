package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Text in the app type scale. Without a [color] it takes the surrounding content
 * color, so labels inside buttons follow the button style.
 */
@Composable
fun AbyssText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AbyssTypography.current.body,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}
