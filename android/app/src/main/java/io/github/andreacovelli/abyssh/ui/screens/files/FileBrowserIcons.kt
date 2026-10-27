package io.github.andreacovelli.abyssh.ui.screens.files

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import io.github.andreacovelli.abyssh.R

/** Terminal icon for the button that returns to the terminal. */
@Composable
fun TerminalIcon(color: Color, modifier: Modifier = Modifier) {
    TintedIcon(R.drawable.ic_terminal, color, modifier)
}

/** Circular arrow icon for reloading the listing. */
@Composable
fun RefreshIcon(color: Color, modifier: Modifier = Modifier) {
    TintedIcon(R.drawable.ic_refresh, color, modifier)
}

/** Copy icon for copying an entry's path. */
@Composable
fun CopyIcon(color: Color, modifier: Modifier = Modifier) {
    TintedIcon(R.drawable.ic_content_copy, color, modifier)
}

/** Decorative icon: the enclosing button carries the label. */
@Composable
private fun TintedIcon(@DrawableRes id: Int, color: Color, modifier: Modifier) {
    Image(
        painter = painterResource(id),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier,
    )
}
