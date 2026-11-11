package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.SshLogin
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Logins that finished a multiplexer command, kept for [ttlMs] so the tab the command was
 * for, or the next command, opens its channel on one instead of logging in again. Holds
 * one login per host, user, and credentials; whoever [take]s it owns it. A login nobody
 * takes is closed on [scope] once [ttlMs] passes. Safe to use from any thread.
 */
internal class IdleSshLogins<L : Closeable>(
    private val ttlMs: Long = TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry<L>(val login: L, val expiry: Job)

    private val idle = HashMap<LoginKey, Entry<L>>()

    /** Keeps [login] for [spec]'s host, replacing and closing any login kept for it. */
    fun park(spec: TabSpec, login: L) {
        val key = LoginKey(spec)
        val replaced =
            synchronized(idle) {
                // The expiry takes the lock, so it can't run before the entry is in place.
                val expiry = scope.launch {
                    delay(ttlMs)
                    expire(key, login)
                }
                idle.put(key, Entry(login, expiry))?.also { it.expiry.cancel() }
            }
        replaced?.login?.close()
    }

    /** The login kept for [spec]'s host, which the caller now owns, or null. */
    fun take(spec: TabSpec): L? = synchronized(idle) {
        idle.remove(LoginKey(spec))?.also { it.expiry.cancel() }?.login
    }

    /** Closes every kept login on [scope], since logging out can wait on the network. */
    fun closeAll() {
        val entries =
            synchronized(idle) {
                idle.values.toList().also { idle.clear() }
            }
        entries.forEach { it.expiry.cancel() }
        if (entries.isNotEmpty()) scope.launch { entries.forEach { it.login.close() } }
    }

    private fun expire(key: LoginKey, login: L) {
        val expired =
            synchronized(idle) {
                if (idle[key]?.login === login) idle.remove(key) else null
            }
        expired?.login?.close()
    }

    /**
     * What decides whether two specs log in the same way. Not a data class, so its
     * toString can't print the credentials.
     */
    private class LoginKey(spec: TabSpec) {
        private val fields: List<Any> =
            with(spec) { listOf(transport, host, port, username, authMethod, password, publicKeyOpenSsh, privateKeyPem, keyPassphrase) }

        override fun equals(other: Any?): Boolean = other is LoginKey && other.fields == fields

        override fun hashCode(): Int = fields.hashCode()
    }

    companion object {
        /** Long enough for the tab that a multiplexer check named a session for to open. */
        const val TTL_MS = 30_000L

        /** Shared by the multiplexer checks and the sessions and tmux control links that open after them. */
        val shared = IdleSshLogins<SshLogin>()
    }
}
