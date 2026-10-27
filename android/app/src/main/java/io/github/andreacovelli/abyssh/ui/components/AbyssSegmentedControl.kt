package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val isDisabled = option in disabledOptions
            val label = labels[option] ?: option.toString()
            AbyssButton(
                onClick = { onSelect(option) },
                enabled = !isDisabled,
                variant = AbyssButtonVariant.Outlined,
                borderColor = if (isSelected) colors.accent else colors.border,
                backgroundColor = if (isSelected) colors.accent.copy(alpha = 0.12f) else null,
                modifier = Modifier.weight(1f),
            ) {
                AbyssText(
                    text = label,
                    style = typography.label,
                    color = when {
                        isDisabled -> colors.textMuted
                        isSelected -> colors.accent
                        else -> colors.textSecondary
                    },
                )
            }
        }
    }
}
