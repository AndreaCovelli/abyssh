package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** A setting title and hint beside a switch. */
@Composable
internal fun SettingSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AbyssText(title, style = typography.label)
            AbyssText(hint, style = typography.bodySmall, color = colors.textMuted)
        }
        AbyssSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A [SettingSwitchRow] on its own card. */
@Composable
internal fun SettingSwitchCard(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        SettingSwitchRow(
            title = title,
            hint = hint,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(12.dp),
        )
    }
}

/** A setting title and summary beside a button that opens its editor. */
@Composable
internal fun SettingCustomizeRow(
    title: String,
    summary: String,
    onCustomize: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AbyssText(title, style = typography.label)
            AbyssText(summary, style = typography.body, color = colors.textMuted)
        }
        AbyssButton(
            onClick = onCustomize,
            variant = AbyssButtonVariant.Outlined,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            AbyssText(stringResource(R.string.action_customize), style = typography.label)
        }
    }
}
