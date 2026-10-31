package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlEvent
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlParser
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.ssh.RemoteTerm
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield

private const val READ_CHUNK_BYTES = 65536
private const val DETACH_WAIT_READS = 10
private const val DETACH_WAIT_MS = 20L

/**
 * The SSH channel that runs `tmux -CC`. Commands go out one per line, and tmux answers
 * each with a reply block in the order they were sent, so each reply goes to the oldest
 * callback still waiting. Every call must run on the control session's thread.
 */
internal class TmuxControlLink(hostKeyPolicy: HostKeyPolicy) {
    val ssh = NativeSshService(hostKeyPolicy = hostKeyPolicy)
    private val parser = TmuxControlParser()
    private val pacer = ReadPacer()
    private val replies = ArrayDeque<(TmuxControlEvent.Reply) -> Unit>()

    /** Logs in and starts tmux in control mode on [sessionName], replacing any earlier channel. */
    fun open(spec: TabSpec, sessionName: String, geometry: TerminalGeometry) {
        close()
        check(ssh.isAvailable()) { "Native SSH unavailable" }
        ssh.connect(spec)
        ssh.openExecPty(
            TmuxControlCommands.attach(sessionName, spec.multiplexerCreateIfMissing),
            geometry.cols,
            geometry.rows,
            geometry.ptyWidthPx,
            geometry.ptyHeightPx,
            RemoteTerm.SSH,
        )
    }

    /** Sends [command]; [onReply] gets tmux's reply. */
    fun send(command: String, onReply: (TmuxControlEvent.Reply) -> Unit = {}) = sendAll(listOf(command)) { onReply(it.single()) }

    /** Sends [commands] in one write, so tmux runs them back to back; [onReplies] gets their replies in order. */
    fun sendAll(commands: List<String>, onReplies: (List<TmuxControlEvent.Reply>) -> Unit = {}) {
        if (commands.isEmpty()) return
        val collected = ArrayList<TmuxControlEvent.Reply>(commands.size)
        repeat(commands.size) {
            replies.addLast { reply ->
                collected += reply
                if (collected.size == commands.size) onReplies(collected)
            }
        }
        pacer.onInput()
        ssh.write(commands.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8))
    }

    /**
     * Passes tmux's notifications to [onEvent] and its replies to their callbacks until the
     * channel closes or the loop is cancelled.
     */
    suspend fun read(onEvent: (TmuxControlEvent) -> Unit) {
        pacer.onActivity()
        while (currentCoroutineContext().isActive) {
            val chunk = ssh.read(READ_CHUNK_BYTES) ?: return
            when {
                chunk.isNotEmpty() -> {
                    pacer.onActivity()
                    parser.feed(chunk) { event ->
                        if (event is TmuxControlEvent.Reply) replies.removeFirstOrNull()?.invoke(event) else onEvent(event)
                    }
                    yield()
                }
                ssh.isChannelEof() -> return
                else -> {
                    if (ssh.hasPendingWrites) pacer.onActivity()
                    pacer.idle()
                }
            }
        }
    }

    /** Asks tmux to detach this client and gives it a moment to go before closing, so the session keeps running cleanly. */
    fun detachAndClose() {
        runCatching {
            ssh.write((TmuxControlCommands.DETACH + "\n").toByteArray(Charsets.UTF_8))
            repeat(DETACH_WAIT_READS) {
                if (ssh.read(READ_CHUNK_BYTES) == null || ssh.isChannelEof()) return@runCatching
                Thread.sleep(DETACH_WAIT_MS)
            }
        }
        close()
    }

    fun close() {
        ssh.close()
        parser.reset()
        replies.clear()
    }
}
