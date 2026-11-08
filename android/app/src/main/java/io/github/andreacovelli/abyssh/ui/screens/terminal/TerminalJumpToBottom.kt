package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Shown while the view is [scrolledBack] from the live output: brings it back down. It
 * reads the snapshot through a lambda, so a new frame recomposes only this.
 */
@Composable
internal fun TerminalJumpToBottom(scrolledBack: () -> Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val current by rememberUpdatedState(scrolledBack)
    val visible by remember { derivedStateOf { current() } }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        AbyssButton(
            onClick = onClick,
            variant = AbyssButtonVariant.Outlined,
            backgroundColor = colors.surface,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_downward),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                AbyssText(
                    stringResource(R.string.terminal_jump_to_bottom),
                    style = AbyssTypography.current.labelSmall,
                    color = colors.accent,
                )
            }
        }
    }
}
