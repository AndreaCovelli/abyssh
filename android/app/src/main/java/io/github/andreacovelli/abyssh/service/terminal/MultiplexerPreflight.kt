package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs multiplexer checks before a tab opens, one at a time. A host key the store doesn't
 * trust ends the check's SSH session, so no live session waits on the user; once the user
 * accepts the key, the check runs again. [cancel] ends any check that started before it
 * without showing a prompt.
 */
internal class MultiplexerPreflight(
    private val hostKeyStore: HostKeyStore,
    private val newProbe: (HostKeyPolicy) -> RemoteMultiplexerProbe = { RemoteMultiplexerProbe(hostKeyPolicy = it) },
) {
    private class UntrustedKey(val prompt: HostKeyPrompt, val keyBytes: ByteArray)

    private val mutex = Mutex()
    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    // Counts cancel() calls. A check remembers the count it started with and stops before
    // prompting or saving a key once the count has moved on.
    private val generation = AtomicInteger()

    suspend fun <T> run(block: (RemoteMultiplexerProbe) -> T): T {
        val started = generation.get()
        return mutex.withLock { runUntilTrusted(started, block) }
    }

    /** Answers the waiting host key prompt; returns false when no check is waiting. */
    fun respond(accepted: Boolean): Boolean {
        val pending = decision ?: return false
        pending.complete(accepted)
        return true
    }

    fun cancel() {
        generation.incrementAndGet()
        decision?.cancel()
        decision = null
        _hostKeyPrompt.value = null
    }

    // The probe reports a refused key as whatever its connect step throws, so any failure
    // after an untrusted key was seen is treated as that refusal.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runUntilTrusted(started: Int, block: (RemoteMultiplexerProbe) -> T): T {
        while (true) {
            var untrusted: UntrustedKey? = null
            val probe =
                newProbe { host, port, algorithm, keyBytes ->
                    val prompt = hostKeyStore.promptFor(host, port, algorithm, keyBytes)
                    if (prompt != null) untrusted = UntrustedKey(prompt, keyBytes.copyOf())
                    prompt == null
                }
            val key =
                try {
                    return withContext(Dispatchers.IO) { block(probe) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    untrusted ?: throw error
                }
            check(awaitDecision(started, key.prompt)) { "Host key rejected" }
            ensureCurrent(started)
            with(key.prompt) { hostKeyStore.saveKey(host, port, algorithm, key.keyBytes) }
        }
    }

    private suspend fun awaitDecision(started: Int, prompt: HostKeyPrompt): Boolean {
        val pending = CompletableDeferred<Boolean>()
        // Publish the decision before checking the count; cancel() moves the count before
        // reading the decision. Either this sees the new count or cancel() cancels pending.
        decision = pending
        return try {
            ensureCurrent(started)
            _hostKeyPrompt.value = prompt
            pending.await()
        } finally {
            if (decision === pending) decision = null
            _hostKeyPrompt.value = null
        }
    }

    private fun ensureCurrent(started: Int) {
        check(generation.get() == started) { "Disconnected while preparing the connection" }
    }
}
