package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

/** Chevron for a picker that opens a list: down when closed, up when open. */
@Composable
fun AbyssExpandIcon(expanded: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
        contentDescription = null,
        tint = AbyssColors.current.textMuted,
        modifier = modifier.size(20.dp),
    )
}
