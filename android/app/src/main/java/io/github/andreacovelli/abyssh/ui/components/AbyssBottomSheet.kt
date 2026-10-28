package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Panel that slides up from the bottom of its parent over a dimming scrim. Tapping the
 * scrim calls [onDismiss]. Place it last in a full-size `Box` so it draws above the
 * screen content.
 *
 * @param heightFraction fraction of the parent height the panel fills, or `null` to wrap
 *   its content.
 */
@Composable
fun AbyssBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float? = null,
    scrimAlpha: Float = 0.72f,
    verticalSpacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AbyssColors.current
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
                    .background(colors.surfaceVariant)
                    // Swallow taps so they don't reach the scrim behind the panel.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                content = content,
            )
        }
    }
}

/** Title row for an [AbyssBottomSheet], with an optional subtitle and a close button. */
@Composable
fun AbyssSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = if (subtitle == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AbyssText(title, style = typography.headline)
            if (subtitle != null) {
                AbyssText(subtitle, style = typography.body, color = colors.textSecondary)
            }
        }
        AbyssCloseButton(onClick = onClose, contentDescription = stringResource(R.string.action_close))
    }
}
