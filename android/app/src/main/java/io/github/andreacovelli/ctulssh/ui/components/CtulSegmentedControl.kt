package io.github.andreacovelli.ctulssh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import io.github.andreacovelli.ctulssh.ui.theme.CtulTypography

@Composable
fun <T> CtulSegmentedControl(
    options: List<T>,
    labels: Map<T, String>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    disabledOptions: Set<T> = emptySet(),
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val isDisabled = option in disabledOptions
            val label = labels[option] ?: option.toString()
            CtulButton(
                onClick = { onSelect(option) },
                enabled = !isDisabled,
                variant = CtulButtonVariant.Outlined,
                borderColor = if (isSelected) colors.accent else colors.border,
                backgroundColor = if (isSelected) colors.accent.copy(alpha = 0.12f) else null,
                modifier = Modifier.weight(1f),
            ) {
                CtulText(
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
