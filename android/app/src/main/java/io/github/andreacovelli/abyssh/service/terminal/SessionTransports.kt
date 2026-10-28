package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.service.mosh.NativeMoshService
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.ssh.RemoteTerm
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val READ_CHUNK_BYTES = 65536

// Idle thresholds and the poll delay for each tier.
private const val ACTIVE_WINDOW_MS = 50L
private const val NEAR_IDLE_WINDOW_MS = 500L
private const val IDLE_WINDOW_MS = 3_000L
private const val MIN_READ_DELAY_MS = 2L
private const val NEAR_IDLE_DELAY_MS = 8L
private const val IDLE_DELAY_MS = 24L
private const val MAX_READ_DELAY_MS = 64L

/**
 * Read-loop poll interval as a function of how long the session has been idle: MIN while
 * data is flowing (snappy echo), ramping to MAX once quiet so an idle terminal stops
 * spinning. The first byte after idle restores MIN within one MAX interval.
 */
internal fun idleReadDelayMs(idleForMs: Long): Long = when {
    idleForMs < ACTIVE_WINDOW_MS -> MIN_READ_DELAY_MS
    idleForMs < NEAR_IDLE_WINDOW_MS -> NEAR_IDLE_DELAY_MS
    idleForMs < IDLE_WINDOW_MS -> IDLE_DELAY_MS
    else -> MAX_READ_DELAY_MS
}

/**
 * The SSH, Mosh, and local shell services behind one session, and which of them the
 * session uses. Every call must run on the session thread.
 */
internal class SessionTransports(hostKeyPolicy: HostKeyPolicy, val localShell: NativeLocalShellService) {
    val ssh = NativeSshService(hostKeyPolicy = hostKeyPolicy)
    val mosh = MoshTransport(ssh, NativeMoshService())
    private var transport: Transport = Transport.SSH

    /** Why [transport]'s native library can't run, or null when it can. */
    fun unavailableReason(transport: Transport): UiText? = when {
        transport == Transport.Mosh && !mosh.isLoaded -> UiText.Resource(R.string.error_native_mosh_unavailable)
        transport.runsRemoteCommands && !ssh.isAvailable() -> UiText.Resource(R.string.error_native_ssh_unavailable)
        else -> null
    }

    fun closeAll() {
        ssh.close()
        mosh.close()
        localShell.close()
    }

    suspend fun open(spec: TabSpec, geometry: TerminalGeometry) {
        transport = spec.transport
        when (spec.transport) {
            Transport.Mosh -> mosh.open(spec, geometry)
            Transport.LocalShell ->
                localShell.start(geometry.cols, geometry.rows, geometry.screenWidth, geometry.screenHeight)
            else -> openSsh(spec, geometry)
        }
    }

    fun write(data: ByteArray) {
        when (transport) {
            Transport.Mosh -> mosh.sendInput(data)
            Transport.LocalShell -> localShell.write(data)
            else -> ssh.write(data)
        }
    }

    fun resize(geometry: TerminalGeometry) {
        when (transport) {
            Transport.Mosh -> mosh.resize(geometry.cols, geometry.rows)
            Transport.LocalShell ->
                localShell.resize(geometry.cols, geometry.rows, geometry.ptyWidthPx, geometry.ptyHeightPx)
            else -> ssh.resize(geometry.cols, geometry.rows, geometry.ptyWidthPx, geometry.ptyHeightPx)
        }
    }

    /** Feeds SSH output to [onChunk]; returns true when the remote shell ended on its own (exit, Ctrl+D). */
    suspend fun readSsh(onChunk: (ByteArray) -> Unit): Boolean {
        var lastActivityMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val chunk = ssh.read(READ_CHUNK_BYTES) ?: break
            when {
                chunk.isNotEmpty() -> {
                    lastActivityMs = System.currentTimeMillis()
                    onChunk(chunk)
                }
                // The native read reports a closed channel the same way as "no data
                // yet", so check EOF here; otherwise a finished shell is polled forever.
                ssh.isChannelEof() -> return true
                // Adaptive poll: stay snappy while data is flowing, back off when
                // idle so a quiet session doesn't spin at 500 wakeups/sec.
                else -> delay(idleReadDelayMs(System.currentTimeMillis() - lastActivityMs))
            }
        }
        return false
    }

    /** Feeds the local shell's output to [onChunk] until the shell ends or the loop is cancelled. */
    suspend fun readLocalShell(onChunk: (ByteArray) -> Unit) {
        var lastActivityMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val chunk = localShell.read(READ_CHUNK_BYTES) ?: break
            if (chunk.isNotEmpty()) {
                lastActivityMs = System.currentTimeMillis()
                onChunk(chunk)
            } else {
                delay(idleReadDelayMs(System.currentTimeMillis() - lastActivityMs))
            }
        }
    }

    private fun openSsh(spec: TabSpec, geometry: TerminalGeometry) {
        check(ssh.isAvailable()) { "Native SSH unavailable" }
        ssh.connect(spec)
        val width = geometry.ptyWidthPx
        val height = geometry.ptyHeightPx
        val startupCommand = spec.multiplexerStartupCommand()?.trim().orEmpty()
        if (startupCommand.isNotEmpty()) {
            ssh.openExecPty(startupCommand, geometry.cols, geometry.rows, width, height, RemoteTerm.SSH)
        } else {
            ssh.openShell(geometry.cols, geometry.rows, width, height, RemoteTerm.SSH)
        }
    }
}
