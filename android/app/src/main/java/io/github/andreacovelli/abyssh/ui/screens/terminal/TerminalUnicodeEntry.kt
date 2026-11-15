package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.terminal.UnicodeEntry
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * The code point of a Ctrl+Shift+U entry under way, with the character it names, so the
 * digits typed so far are visible: nothing reaches the terminal until the entry ends.
 */
@Composable
internal fun TerminalUnicodeEntry(digits: String, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val character = UnicodeEntry.codePointText(digits)?.takeUnless { Character.isISOControl(it.codePointAt(0)) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(
            text = "U+${digits.uppercase()}" + character?.let { "  $it" }.orEmpty(),
            style = typography.codeSmall,
            color = colors.textPrimary,
        )
        AbyssText(
            text = stringResource(R.string.terminal_unicode_entry_hint),
            style = typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}
