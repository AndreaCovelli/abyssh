package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
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
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp)
            .testTag("terminal_link_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AbyssText(
            text = url,
            style = typography.codeSmall,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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
