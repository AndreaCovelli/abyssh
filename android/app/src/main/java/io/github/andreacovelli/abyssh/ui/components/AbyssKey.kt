package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors

private val KeyShape: Shape = RoundedCornerShape(8.dp)

/**
 * Compact rounded key for dense rows such as the terminal accessory bar. Unlike
 * [AbyssButton] it has no minimum size, so short labels like `Esc` stay narrow and
 * keys in a row keep even gaps.
 *
 * [active] fills the key with the accent color, for latched modifiers and open groups.
 * Leave [onClick] null when the caller handles touches itself and passes [pressed].
 */
@Composable
fun AbyssKey(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    active: Boolean = false,
    pressed: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
    content: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val container = when {
        active -> colors.accent
        pressed -> lerp(colors.surface, colors.accent, 0.3f)
        else -> colors.surface
    }
    val contentColor = if (active) colors.onAccent else colors.textPrimary
    val border = if (active) null else BorderStroke(1.dp, colors.border.copy(alpha = 0.6f))
    val body: @Composable () -> Unit = {
        Box(Modifier.padding(contentPadding), contentAlignment = Alignment.Center) { content() }
    }
    if (onClick != null) {
        // A clickable Surface pads itself to 48dp, which spreads narrow keys apart.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Surface(
                onClick = onClick,
                modifier = modifier,
                shape = KeyShape,
                color = container,
                contentColor = contentColor,
                border = border,
                content = body,
            )
        }
    } else {
        Surface(
            modifier = modifier,
            shape = KeyShape,
            color = container,
            contentColor = contentColor,
            border = border,
            content = body,
        )
    }
}
