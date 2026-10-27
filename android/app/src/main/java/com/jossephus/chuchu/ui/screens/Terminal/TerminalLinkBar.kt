package com.jossephus.chuchu.ui.screens.Terminal

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
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/** The tapped link in full, with open and copy. */
@Composable
fun TerminalLinkBar(
    url: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
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
        ChuText(
            text = url,
            style = typography.labelSmall,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LinkBarButton("open", onOpen, "link_open")
        LinkBarButton("copy", onCopy, "link_copy")
        LinkBarButton("x", onDismiss, "link_dismiss")
    }
}

@Composable
private fun LinkBarButton(label: String, onClick: () -> Unit, testTag: String) {
    ChuButton(
        onClick = onClick,
        variant = ChuButtonVariant.Ghost,
        bracketed = label != "x",
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        testTag = testTag,
    ) {
        ChuText(label, style = ChuTypography.current.labelSmall, color = ChuColors.current.accent)
    }
}
