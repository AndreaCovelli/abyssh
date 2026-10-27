package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AbyssSegmentedControl(
    options: List<T>,
    labels: Map<T, String>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    disabledOptions: Set<T> = emptySet(),
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = option !in disabledOptions,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.2f),
                    activeContentColor = colors.textPrimary,
                    activeBorderColor = colors.border,
                    inactiveContainerColor = colors.background.copy(alpha = 0f),
                    inactiveContentColor = colors.textSecondary,
                    inactiveBorderColor = colors.border,
                    disabledInactiveContentColor = colors.textMuted,
                    disabledInactiveBorderColor = colors.border.copy(alpha = 0.5f),
                ),
                label = {
                    AbyssText(
                        text = labels[option] ?: option.toString(),
                        style = typography.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
