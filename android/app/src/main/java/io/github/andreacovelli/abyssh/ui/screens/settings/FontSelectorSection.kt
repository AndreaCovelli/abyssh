package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssExpandIcon
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssFontOption
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
internal fun FontSelectorSection(
    currentFont: String,
    onFontSelected: (String) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val current = remember(currentFont) { AbyssFontOption.fromId(currentFont) }
    var expanded by remember { mutableStateOf(false) }

    AbyssSectionHeader(stringResource(R.string.settings_section_font))
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AbyssButton(
            onClick = { expanded = !expanded },
            variant = AbyssButtonVariant.Outlined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AbyssText(current.label, style = typography.label)
                AbyssExpandIcon(expanded)
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                AbyssFontOption.entries.forEach { option ->
                    val isSelected = option == current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) colors.surface else colors.surfaceVariant)
                            .clickable {
                                onFontSelected(option.id)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AbyssText(
                                option.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                            )
                            if (isSelected) AbyssText("●", style = typography.label, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}
