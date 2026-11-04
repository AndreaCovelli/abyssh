package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.terminal.HostKeyPrompt
import io.github.andreacovelli.abyssh.service.terminal.HostKeyVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the editor's "Test connection": connects and authenticates once, then closes.
 * An unknown or changed host key is asked about through [hostKeyPrompt], as connecting does.
 */
internal class ConnectionTester(
    private val scope: CoroutineScope,
    hostKeyStore: HostKeyStore,
    private val localNetwork: LocalNetworkAccess,
) {
    private val _state = MutableStateFlow(ConnectionTestState())
    val state: StateFlow<ConnectionTestState> = _state.asStateFlow()

    private val hostKeys = HostKeyVerifier(hostKeyStore)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = hostKeys.prompt

    private var job: Job? = null

    fun test(form: AddServerForm) {
        val host = form.host.filterNot { it.isWhitespace() }
        val port = form.portNumber
        val username = form.username.trim()
        if (host.isBlank() || username.isBlank() || port == null) return
        if (_state.value.status == ConnectionTestStatus.Running) return
        _state.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { connect(form, host, port, username) } }
            _state.value =
                if (result.isSuccess) {
                    ConnectionTestState(status = ConnectionTestStatus.Success)
                } else {
                    val fallback = UiText.Resource(R.string.terminal_connection_failed)
                    ConnectionTestState(
                        status = ConnectionTestStatus.Error,
                        message = result.exceptionOrNull()?.toUiText(fallback) ?: fallback,
                    )
                }
        }
    }

    fun onHostKeyDecision(accept: Boolean) = hostKeys.respond(accept)

    /** Drops the result, or stops a test that is still running. */
    fun reset() {
        if (_state.value.status == ConnectionTestStatus.Idle) return
        job?.cancel()
        job = null
        hostKeys.cancel()
        _state.value = ConnectionTestState()
    }

    /** Unblocks a test waiting on the host key prompt, for when the editor closes. */
    fun close() = hostKeys.cancel()

    private fun connect(form: AddServerForm, host: String, port: Int, username: String) {
        if (localNetwork.blocks(host)) {
            throw UiTextException(UiText.Resource(R.string.error_local_network_denied), "Local network access denied")
        }
        NativeSshService(hostKeyPolicy = hostKeys).use { nativeSsh ->
            if (!nativeSsh.isAvailable()) {
                throw UiTextException(UiText.Resource(R.string.error_native_ssh_unavailable), "Native SSH unavailable")
            }
            nativeSsh.connect(
                host = host,
                port = port,
                username = username,
                authMethod = form.authMethod,
                password = if (form.authMethod == AuthMethod.Password) form.password else "",
                publicKeyOpenSsh = form.publicKeyOpenSsh,
                privateKeyPem = form.privateKeyPem,
                keyPassphrase = form.keyPassphrase,
            )
        }
    }
}
