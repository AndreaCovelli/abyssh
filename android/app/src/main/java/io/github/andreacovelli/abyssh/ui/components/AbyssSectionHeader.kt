package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** Accent-colored label that opens a group of settings or list items. */
@Composable
fun AbyssSectionHeader(label: String, modifier: Modifier = Modifier) {
    AbyssText(
        label,
        style = AbyssTypography.current.label,
        color = AbyssColors.current.accent,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 4.dp, top = 4.dp),
    )
}
