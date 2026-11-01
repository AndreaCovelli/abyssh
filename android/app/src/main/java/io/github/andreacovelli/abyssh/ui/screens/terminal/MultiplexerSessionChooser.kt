package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * Asks which session a new tab attaches to, on a host set to choose its session on
 * connect. Nothing is created unless the user picks a new session; cancelling runs
 * [onBack] when the tab was the first one its route opened.
 */
@Composable
internal fun MultiplexerSessionChooserDialog(vm: TerminalViewModel, onBack: () -> Unit) {
    val state by vm.multiplexer.state.collectAsStateWithLifecycle()
    val choice = state.sessionChoice ?: return
    val host = state.preflightHostLabel ?: stringResource(R.string.terminal_host_fallback)
    AbyssDialog(
        title = stringResource(R.string.multiplexer_choose_title, choice.type.label),
        confirmLabel = stringResource(R.string.multiplexer_new_session),
        onConfirm = vm.multiplexer::chooseNewSession,
        onDismiss = { vm.multiplexer.dismissRecovery(onBack) },
    ) {
        val rows = multiplexerChoiceRows(choice.sessions, choice.openInTab)
        if (rows.isEmpty()) {
            AbyssText(
                stringResource(R.string.multiplexer_choose_empty, host),
                style = AbyssTypography.current.body,
                color = AbyssColors.current.textSecondary,
            )
            return@AbyssDialog
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows, key = { it.name }) { row ->
                MultiplexerSessionRow(
                    row = row,
                    onAttach = { vm.multiplexer.chooseSession(row.name) },
                    onEnd = null,
                )
            }
        }
    }
}
