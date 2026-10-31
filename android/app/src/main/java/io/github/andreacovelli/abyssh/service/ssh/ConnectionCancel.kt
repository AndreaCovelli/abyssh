package io.github.andreacovelli.abyssh.service.ssh

import io.github.andreacovelli.abyssh.service.terminal.NativeWakeSignal
import io.github.andreacovelli.abyssh.service.terminal.WakeSignal

/**
 * The eventfd that cancels one SSH connection while it connects. The native socket waits
 * listen to it, so a signal ends a TCP connect, handshake, or channel open at once.
 *
 * [cancel] may run on any thread. The lock keeps it from signalling a descriptor that
 * [close] already closed, whose number the system may have given to something else.
 */
internal class ConnectionCancel(private val wake: WakeSignal = NativeWakeSignal()) {
    private val lock = Any()
    private var fd = -1

    /** Whether [cancel] ran since the current descriptor was opened. */
    @Volatile var requested = false
        private set

    /** Replaces the descriptor with a new one for the next connection; returns it, or -1. */
    fun open(): Int = synchronized(lock) {
        closeLocked()
        requested = false
        fd = wake.create()
        fd
    }

    fun cancel() = synchronized(lock) {
        requested = true
        if (fd >= 0) wake.signal(fd)
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        if (fd >= 0) wake.close(fd)
        fd = -1
    }
}
