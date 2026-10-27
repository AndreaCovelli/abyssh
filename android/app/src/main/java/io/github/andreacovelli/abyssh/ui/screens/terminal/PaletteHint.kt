package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Hint chip showing a key binding and its action, used in overlay bottom bars.
 */
@Composable
fun PaletteHint(key: String, label: String) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .border(1.dp, colors.border)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            AbyssText(key, style = typography.labelSmall, color = colors.textSecondary)
        }
        AbyssText(label, style = typography.labelSmall, color = colors.textMuted)
    }
}
