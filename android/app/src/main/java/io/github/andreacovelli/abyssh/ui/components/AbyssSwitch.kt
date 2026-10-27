package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

@Composable
fun AbyssSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AbyssColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textMuted,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.border,
        ),
    )
}
