package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.asString
import io.github.andreacovelli.abyssh.ui.terminal.AbysshHint
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

private val MenuShape = RoundedCornerShape(16.dp)
private val ItemShape = RoundedCornerShape(10.dp)
private val KeycapShape = RoundedCornerShape(6.dp)

// Grows out of the bolt key, which sits at the bottom-left corner of the accessory bar.
private val MenuOrigin = TransformOrigin(0f, 1f)

/**
 * While the shortcut prefix waits for its second key, a menu of the keys it takes.
 * Typing a key or tapping its item runs the command. The menu is as wide as its
 * content, in two columns so it covers less of the terminal.
 */
@Composable
internal fun AbysshShortcutMenu(keys: AbysshKeyBindings, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    AnimatedVisibility(
        visible = keys.isPrefixActive,
        enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = MenuOrigin),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = MenuOrigin),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 112.dp, max = 280.dp),
            shape = MenuShape,
            color = colors.surface,
            contentColor = colors.textPrimary,
            shadowElevation = 3.dp,
        ) {
            val hints = keys.hints()
            // Column by column, so screen readers read the items in command order.
            val columns = hints.chunked(((hints.size + 1) / 2).coerceAtLeast(1))
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                columns.forEach { column ->
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        column.forEach { hint ->
                            ShortcutItem(hint, onClick = { keys.runShortcut(hint.key) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutItem(hint: AbysshHint, onClick: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier
            .widthIn(max = 134.dp)
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(ItemShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 24.dp)
                .clip(KeycapShape)
                .background(colors.accent.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            AbyssText(hint.key.toString(), style = typography.code, color = colors.accent)
        }
        AbyssText(
            text = hint.description.asString(),
            style = typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
