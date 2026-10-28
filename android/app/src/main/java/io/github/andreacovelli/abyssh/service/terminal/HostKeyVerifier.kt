package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.HostKeyCheck
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** What to ask the user about a host key the store doesn't trust, or null when it matches. */
internal fun HostKeyStore.promptFor(host: String, port: Int, algorithm: String, keyBytes: ByteArray): HostKeyPrompt? =
    when (val result = check(host, port, algorithm, keyBytes)) {
        is HostKeyCheck.Match -> null
        is HostKeyCheck.Unknown -> HostKeyPrompt(host, port, algorithm, result.fingerprint, null)
        is HostKeyCheck.Changed ->
            HostKeyPrompt(host, port, algorithm, result.fingerprint, result.previousFingerprint)
    }

/**
 * Asks the user about host keys the store doesn't trust. [verify] runs on the session
 * thread during the SSH handshake and blocks it until [respond] or [cancel].
 */
internal class HostKeyVerifier(private val store: HostKeyStore) : HostKeyPolicy {
    private val _prompt = MutableStateFlow<HostKeyPrompt?>(null)
    val prompt: StateFlow<HostKeyPrompt?> = _prompt.asStateFlow()

    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    override fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean {
        val prompt = store.promptFor(host, port, algorithm, keyBytes) ?: return true
        val deferred =
            decision
                ?: CompletableDeferred<Boolean>().also {
                    decision = it
                    _prompt.value = prompt
                }
        val accepted = runBlocking { deferred.await() }
        if (accepted) store.saveKey(host, port, algorithm, keyBytes)
        return accepted
    }

    fun respond(accepted: Boolean) {
        decision?.complete(accepted)
        clear()
    }

    fun cancel() {
        decision?.cancel()
        clear()
    }

    private fun clear() {
        decision = null
        _prompt.value = null
    }
}
