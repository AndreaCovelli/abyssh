package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** The tapped link in full, with open and copy. */
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(colors.surface)
            .border(1.dp, colors.border)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("terminal_link_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AbyssText(
            text = url,
            style = typography.labelSmall,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LinkBarButton(stringResource(R.string.action_open), onOpen, "link_open")
        LinkBarButton(stringResource(R.string.action_copy), onCopy, "link_copy")
        LinkBarButton("x", onDismiss, "link_dismiss")
    }
}

@Composable
private fun LinkBarButton(label: String, onClick: () -> Unit, testTag: String) {
    AbyssButton(
        onClick = onClick,
        variant = AbyssButtonVariant.Ghost,
        bracketed = label != "x",
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        testTag = testTag,
    ) {
        AbyssText(label, style = AbyssTypography.current.labelSmall, color = AbyssColors.current.accent)
    }
}
