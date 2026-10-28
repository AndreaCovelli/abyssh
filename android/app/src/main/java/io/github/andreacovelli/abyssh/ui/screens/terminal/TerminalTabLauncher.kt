package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.security.VerificationResult
import io.github.andreacovelli.abyssh.ui.security.requireUserVerification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens tabs from the terminal screen. A host may first ask for device verification,
 * and a key whose passphrase isn't saved asks for it before the tab opens.
 */
@Stable
internal class TerminalTabLauncher(
    private val vm: TerminalViewModel,
    private val context: Context,
    private val resources: Resources,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
) {
    /** The tab waiting for its key passphrase, or null when no prompt shows. */
    var pendingSpec by mutableStateOf<TabSpec?>(null)
        private set
    var passphrase by mutableStateOf("")

    // Whether cancelling the prompt leaves the screen: true for the tab the route opens.
    private var leaveOnCancel = false

    fun open(spec: TabSpec, requiresVerification: Boolean, leaveOnCancel: Boolean = false) {
        if (!requiresVerification) {
            openOrPrompt(spec, leaveOnCancel)
            return
        }
        requireUserVerification(
            context = context,
            title = resources.getString(R.string.terminal_verify_title),
            subtitle = resources.getString(R.string.terminal_verify_subtitle),
        ) { result ->
            if (result == VerificationResult.Success && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                openOrPrompt(spec, leaveOnCancel)
            }
        }
    }

    /** Opens a tab on saved host [hostId]. */
    fun openHost(hostId: Long) {
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { vm.prepareTabOpenForHost(hostId) } ?: return@launch
            open(prepared.spec, prepared.requiresVerification)
        }
    }

    /** Opens another tab like the active one, or a first tab on [hostId] when none is open. */
    fun openAnotherSession(hostId: Long?) {
        when {
            vm.activeTab.value != null -> {
                vm.duplicateTab()
                vm.files.selectPane(ConnectionTab.Terminal)
            }
            hostId != null -> openHost(hostId)
        }
    }

    fun confirm() {
        val spec = pendingSpec
        val typed = passphrase
        reset()
        if (spec != null) launch(spec.copy(keyPassphrase = typed))
    }

    fun dismiss(onBack: () -> Unit) {
        val leave = leaveOnCancel
        reset()
        if (leave) onBack()
    }

    fun reset() {
        pendingSpec = null
        passphrase = ""
        leaveOnCancel = false
    }

    private fun openOrPrompt(spec: TabSpec, leaveOnCancel: Boolean) {
        if (spec.authMethod == AuthMethod.KeyWithPassphrase && spec.keyPassphrase.isBlank()) {
            this.leaveOnCancel = leaveOnCancel
            pendingSpec = spec
        } else {
            launch(spec)
        }
    }

    private fun launch(spec: TabSpec) {
        if (spec.usesRuntimeMultiplexer) vm.multiplexer.open(spec) else vm.openTab(spec)
    }
}

@Composable
internal fun rememberTerminalTabLauncher(vm: TerminalViewModel): TerminalTabLauncher {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    return remember(vm, context, resources, lifecycle, scope) {
        TerminalTabLauncher(vm, context, resources, lifecycle, scope)
    }
}

@Composable
internal fun TerminalPassphraseDialog(launcher: TerminalTabLauncher, onBack: () -> Unit) {
    if (launcher.pendingSpec == null) return
    AbyssDialog(
        title = stringResource(R.string.terminal_key_passphrase_title),
        confirmLabel = stringResource(R.string.action_connect),
        onConfirm = launcher::confirm,
        onDismiss = { launcher.dismiss(onBack) },
    ) {
        AbyssTextField(
            value = launcher.passphrase,
            onValueChange = { launcher.passphrase = it },
            label = stringResource(R.string.key_passphrase_label),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
