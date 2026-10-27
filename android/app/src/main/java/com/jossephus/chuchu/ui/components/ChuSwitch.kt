package com.jossephus.chuchu.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors

@Composable
fun ChuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ChuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    // The track stays unfilled: a filled accent track left a light block on the left
    // of an "on" switch that read like an "off" thumb, and an off track drawn with
    // `surface` vanished inside cards. Thumb side and color alone carry the state.
    val borderColor = if (checked) colors.accent else colors.border
    val thumbColor = if (checked) colors.accent else colors.textMuted
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 2.dp, label = "chu-switch")

    Box(
        modifier = modifier
            // Fade the whole control when disabled, not just the thumb.
            .alpha(if (enabled) 1f else 0.5f)
            .size(width = 36.dp, height = 18.dp)
            .border(1.dp, borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(14.dp)
                .background(thumbColor),
        )
    }
}
