package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssSegmentedControl
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** Chooses when typed text shows before the host echoes it. */
@Composable
internal fun EchoPredictionCard(
    mode: EchoPredictionMode,
    onModeChanged: (EchoPredictionMode) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val labels = mapOf(
        EchoPredictionMode.Off to stringResource(R.string.settings_echo_prediction_off),
        EchoPredictionMode.Auto to stringResource(R.string.settings_echo_prediction_auto),
        EchoPredictionMode.Always to stringResource(R.string.settings_echo_prediction_always),
    )

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AbyssText(stringResource(R.string.settings_echo_prediction), style = typography.label)
                AbyssText(
                    stringResource(R.string.settings_echo_prediction_hint),
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            AbyssSegmentedControl(
                options = EchoPredictionMode.entries,
                labels = labels,
                selected = mode,
                onSelect = onModeChanged,
            )
        }
    }
}
