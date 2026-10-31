package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * The tapped link in full, with open and copy. It floats like a snackbar: as wide as
 * the link, up to [MaxWidth], so it doesn't span a tablet's screen.
 */
@Composable
fun TerminalLinkBar(
    url: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Surface(
        modifier = modifier
            .padding(8.dp)
            .widthIn(max = MaxWidth)
            .testTag("terminal_link_bar"),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        contentColor = colors.textPrimary,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AbyssText(
                text = url,
                style = typography.codeSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Takes the space the buttons leave, but no more, so a short link keeps the bar narrow.
                modifier = Modifier.weight(1f, fill = false),
            )
            LinkBarButton(stringResource(R.string.action_open), onOpen, "link_open")
            LinkBarButton(stringResource(R.string.action_copy), onCopy, "link_copy")
            AbyssCloseButton(
                onClick = onDismiss,
                contentDescription = stringResource(R.string.action_close),
                modifier = Modifier.testTag("link_dismiss"),
            )
        }
    }
}

private val MaxWidth = 560.dp

@Composable
private fun LinkBarButton(label: String, onClick: () -> Unit, testTag: String) {
    AbyssButton(
        onClick = onClick,
        variant = AbyssButtonVariant.Ghost,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        testTag = testTag,
    ) {
        AbyssText(label, style = AbyssTypography.current.label)
    }
}
