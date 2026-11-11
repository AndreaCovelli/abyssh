package io.github.andreacovelli.abyssh.service.ssh

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * A logged-in SSH connection that no [NativeSshService] owns, from
 * [NativeSshService.release] until another service [NativeSshService.adopt]s it. Closing
 * it logs out, unless a service took it first. Safe to use from any thread.
 */
class SshLogin internal constructor(private val bridge: NativeSshBridge, handle: Long) : Closeable {
    private val handle = AtomicLong(handle)

    /** The native session, which the caller now owns, or 0 when it was taken or closed. */
    internal fun take(): Long = handle.getAndSet(0L)

    override fun close() {
        val session = take()
        if (session == 0L) return
        bridge.nativeClose(session)
        bridge.nativeDestroySession(session)
    }
}
