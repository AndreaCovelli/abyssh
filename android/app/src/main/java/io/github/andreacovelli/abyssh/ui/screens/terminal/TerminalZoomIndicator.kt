package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlinx.coroutines.delay

private const val ZOOM_INDICATOR_HOLD_MS = 900L

/**
 * Shows [text], such as the font size during a pinch, and fades out once it has held
 * still for a moment. [changes] counts the steps: each one shows the indicator again,
 * and the count it starts with keeps it hidden, so opening the terminal doesn't show it.
 */
@Composable
internal fun TerminalZoomIndicator(text: String, changes: Int, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val shape = MaterialTheme.shapes.medium
    val initialChanges = remember { changes }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        if (changes == initialChanges) return@LaunchedEffect
        visible = true
        delay(ZOOM_INDICATOR_HOLD_MS)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        AbyssText(
            text = text,
            modifier = Modifier
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = AbyssTypography.current.title,
            color = colors.textPrimary,
        )
    }
}
