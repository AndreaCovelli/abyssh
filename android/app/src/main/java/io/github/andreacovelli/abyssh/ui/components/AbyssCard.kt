package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

@Composable
fun AbyssCard(
    modifier: Modifier = Modifier,
    background: Color = AbyssColors.current.surface,
    border: Color = AbyssColors.current.border,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(
            containerColor = background,
            contentColor = AbyssColors.current.textPrimary,
        ),
        // Soften the default outline; a custom color (selected, connected) stays vivid.
        border = BorderStroke(1.dp, if (border == AbyssColors.current.border) border.copy(alpha = 0.6f) else border),
        content = content,
    )
}
