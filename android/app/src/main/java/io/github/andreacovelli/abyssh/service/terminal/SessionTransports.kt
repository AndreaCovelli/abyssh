package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.service.mosh.NativeMoshService
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.ssh.RemoteTerm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
 * Paces a read loop with [idleReadDelayMs], and cuts the wait short when the user sends
 * input, so the reply is read at the active cadence instead of after a long idle sleep.
 */
internal class ReadPacer(private val clock: () -> Long = System::currentTimeMillis) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastActivityMs = clock()

    /** Output arrived, or a read loop started. */
    fun onActivity() {
        lastActivityMs = clock()
    }

    /** Input was sent; wakes a loop that is waiting in [idle]. */
    fun onInput() {
        onActivity()
        wake.trySend(Unit)
    }

    /** Waits for the next poll, or until [onInput]. */
    suspend fun idle() {
        withTimeoutOrNull(idleReadDelayMs(clock() - lastActivityMs)) { wake.receive() }
    }
}

/**
 * The SSH, Mosh, and local shell services behind one session, and which of them the
 * session uses. Every call must run on the session thread, which [outputWaiter] blocks
 * while the SSH or local shell read loop waits for output.
 */
internal class SessionTransports(
    hostKeyPolicy: HostKeyPolicy,
    val localShell: NativeLocalShellService,
    private val outputWaiter: OutputWaiter,
) {
    val ssh = NativeSshService(hostKeyPolicy = hostKeyPolicy)
    private val pacer = ReadPacer()
    val mosh = MoshTransport(ssh, NativeMoshService(), pacer, outputWaiter)
    private var transport: Transport = Transport.SSH

    /** Why [transport]'s native library can't run, or null when it can. */
    fun unavailableReason(transport: Transport): UiText? = when {
        transport == Transport.Mosh && !mosh.isLoaded -> UiText.Resource(R.string.error_native_mosh_unavailable)
        transport.runsRemoteCommands && !ssh.isAvailable() -> UiText.Resource(R.string.error_native_ssh_unavailable)
        else -> null
    }

    /** Cancels an SSH or Mosh connection that is still connecting. Safe from any thread. */
    fun cancelConnect() = ssh.cancelConnect()

    fun closeAll() {
        ssh.close()
        mosh.shutdown()
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
        pacer.onInput()
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
        pacer.onActivity()
        while (currentCoroutineContext().isActive) {
            val chunk = ssh.read(READ_CHUNK_BYTES) ?: break
            when {
                chunk.isNotEmpty() -> {
                    pacer.onActivity()
                    onChunk(chunk)
                    // Output can arrive faster than it is read, so give queued input,
                    // resizes, and disconnects a turn on the session thread.
                    yield()
                }
                // The native read reports a closed channel the same way as "no data
                // yet", so check EOF here; otherwise a finished shell is polled forever.
                ssh.isChannelEof() -> return true
                // Input the channel hasn't taken yet goes out on the next read, so poll
                // at the active cadence until it has.
                ssh.hasPendingWrites -> {
                    pacer.onActivity()
                    pacer.idle()
                }
                else -> awaitOutput { wakeFd -> ssh.waitReadable(wakeFd, MAX_OUTPUT_WAIT_MS) }
            }
        }
        return false
    }

    /** Feeds the local shell's output to [onChunk] until the shell ends or the loop is cancelled. */
    suspend fun readLocalShell(onChunk: (ByteArray) -> Unit) {
        pacer.onActivity()
        while (currentCoroutineContext().isActive) {
            val chunk = localShell.read(READ_CHUNK_BYTES) ?: break
            if (chunk.isNotEmpty()) {
                pacer.onActivity()
                onChunk(chunk)
                yield()
            } else {
                awaitOutput { wakeFd -> localShell.waitReadable(wakeFd, MAX_OUTPUT_WAIT_MS) }
            }
        }
    }

    /** Blocks until output or other work for the session thread, or polls when it can't. */
    private suspend fun awaitOutput(wait: (wakeFd: Int) -> Int) {
        if (!outputWaiter.awaitOutput(wait)) pacer.idle()
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
