package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import java.io.ByteArrayOutputStream

private const val ESC = 0x1b
private const val BACKSLASH = '\\'.code
private const val OCTAL_DIGITS = 3
private const val OCTAL_RADIX = 8

/** A line longer than this is dropped, so a broken stream can't exhaust memory. */
internal const val TMUX_MAX_LINE_BYTES = 8 * 1024 * 1024

/** What tmux reports in control mode (`tmux -CC`), one event per line or command block. */
internal sealed interface TmuxControlEvent {
    /** The reply to a command this client sent: its output lines, and whether it failed. */
    data class Reply(val lines: List<String>, val failed: Boolean) : TmuxControlEvent

    /** Output from pane [paneId], unescaped. */
    class Output(val paneId: Int, val data: ByteArray) : TmuxControlEvent

    /** tmux stopped sending pane [paneId]'s output, which it discards until the pane continues. */
    data class Pause(val paneId: Int) : TmuxControlEvent

    /** Windows were added to or removed from the session, or a window's active pane changed. */
    data object WindowsChanged : TmuxControlEvent

    /** Window [windowId] has a new layout, so its panes may have new sizes. */
    data class LayoutChanged(val windowId: Int, val layout: String) : TmuxControlEvent

    /** This client is now attached to session [sessionId], named [name]. */
    data class SessionChanged(val sessionId: Int, val name: String) : TmuxControlEvent

    /** The client is leaving control mode: detached, or its session ended. */
    data class Exit(val reason: String?) : TmuxControlEvent
}

/**
 * Splits the bytes of a tmux control mode stream into [TmuxControlEvent]s. Lines inside
 * a `%begin` … `%end` block are command output, even when they start with `%`. Blocks
 * that this client didn't ask for, such as the one tmux sends on attach, are skipped.
 * Not thread-safe.
 */
internal class TmuxControlParser {
    private val line = ByteArrayOutputStream()
    private var discardingLine = false
    private var block: OpenBlock? = null

    private class OpenBlock(val number: String, val fromClient: Boolean) {
        val lines = mutableListOf<String>()
    }

    fun feed(chunk: ByteArray, length: Int = chunk.size, onEvent: (TmuxControlEvent) -> Unit) {
        for (i in 0 until length) {
            val byte = chunk[i].toInt()
            when {
                byte == '\n'.code -> endLine(onEvent)
                discardingLine -> Unit
                line.size() >= TMUX_MAX_LINE_BYTES -> {
                    line.reset()
                    discardingLine = true
                }
                else -> line.write(byte)
            }
        }
    }

    /** Forgets any partial line or block, as when a new connection starts. */
    fun reset() {
        line.reset()
        discardingLine = false
        block = null
    }

    private fun endLine(onEvent: (TmuxControlEvent) -> Unit) {
        if (discardingLine) {
            discardingLine = false
            line.reset()
            return
        }
        var bytes = line.toByteArray()
        line.reset()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
        bytes = stripDcsStart(bytes)
        val open = block
        if (open != null) {
            onBlockLine(open, bytes, onEvent)
        } else {
            parseNotification(bytes)?.let(onEvent)
        }
    }

    private fun onBlockLine(open: OpenBlock, bytes: ByteArray, onEvent: (TmuxControlEvent) -> Unit) {
        val text = String(bytes, Charsets.UTF_8)
        val guard = guardOf(text)
        if (guard != null && (guard.first == "%end" || guard.first == "%error") && guard.second == open.number) {
            block = null
            if (open.fromClient) onEvent(TmuxControlEvent.Reply(open.lines, failed = guard.first == "%error"))
            return
        }
        open.lines += text
    }

    private fun parseNotification(bytes: ByteArray): TmuxControlEvent? {
        if (bytes.isEmpty() || bytes[0] != '%'.code.toByte()) return null
        val nameEnd = bytes.indexOf(' '.code.toByte()).let { if (it < 0) bytes.size else it }
        val name = String(bytes, 0, nameEnd, Charsets.US_ASCII)
        return when (name) {
            "%output" -> parseOutput(bytes, nameEnd, extended = false)
            "%extended-output" -> parseOutput(bytes, nameEnd, extended = true)
            else -> parseTextNotification(name, String(bytes, Charsets.UTF_8))
        }
    }

    private fun parseTextNotification(name: String, text: String): TmuxControlEvent? {
        val args = text.split(' ')
        return when (name) {
            "%begin" -> {
                guardOf(text)?.let { block = OpenBlock(it.second, fromClient = it.third) }
                null
            }
            "%pause" -> args.getOrNull(1)?.let(::paneId)?.let(TmuxControlEvent::Pause)
            "%window-add", "%window-close", "%unlinked-window-close", "%window-pane-changed" ->
                TmuxControlEvent.WindowsChanged
            "%layout-change" -> {
                val windowId = args.getOrNull(1)?.let(::windowId) ?: return null
                // The visible layout, which shows a zoomed pane at its zoomed size.
                val layout = args.getOrNull(3) ?: args.getOrNull(2) ?: return null
                TmuxControlEvent.LayoutChanged(windowId, layout)
            }
            "%session-changed" -> {
                val sessionId = args.getOrNull(1)?.removePrefix("$")?.toIntOrNull() ?: return null
                TmuxControlEvent.SessionChanged(sessionId, text.substringAfter(' ').substringAfter(' ', ""))
            }
            "%exit" -> TmuxControlEvent.Exit(text.substringAfter(' ', "").ifEmpty { null })
            else -> null
        }
    }

    private fun parseOutput(bytes: ByteArray, nameEnd: Int, extended: Boolean): TmuxControlEvent? {
        val idStart = nameEnd + 1
        if (idStart >= bytes.size) return null
        var idEnd = idStart
        while (idEnd < bytes.size && bytes[idEnd] != ' '.code.toByte()) idEnd++
        val paneId = paneId(String(bytes, idStart, idEnd - idStart, Charsets.US_ASCII)) ?: return null
        val dataStart =
            if (extended) {
                // %extended-output %pane age ... : data
                indexOf(bytes, " : ".toByteArray(Charsets.US_ASCII), idEnd).takeIf { it >= 0 }?.plus(" : ".length)
                    ?: return null
            } else {
                (idEnd + 1).coerceAtMost(bytes.size)
            }
        return TmuxControlEvent.Output(paneId, unescapeOutput(bytes, dataStart))
    }
}

/** The `%begin`, `%end`, or `%error` name, the command number, and whether this client sent the command. */
private fun guardOf(text: String): Triple<String, String, Boolean>? {
    val parts = text.split(' ')
    if (parts.size < 3) return null
    val flags = parts.getOrNull(3)?.toIntOrNull() ?: 0
    return Triple(parts[0], parts[2], flags and 1 != 0)
}

/** Drops the DCS sequence (`ESC P 1000 p`) that starts `tmux -CC` output, and the `ESC \` that ends it. */
private fun stripDcsStart(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0].toInt() != ESC) return bytes
    return when (bytes[1].toInt()) {
        'P'.code -> {
            val end = bytes.indexOf('p'.code.toByte())
            if (end < 0) ByteArray(0) else bytes.copyOfRange(end + 1, bytes.size)
        }
        BACKSLASH -> bytes.copyOfRange(2, bytes.size)
        else -> bytes
    }
}

/** Decodes tmux's `\ooo` octal escapes, which it uses for control characters and backslashes. */
internal fun unescapeOutput(bytes: ByteArray, start: Int = 0): ByteArray {
    val out = ByteArrayOutputStream(bytes.size - start)
    var i = start
    while (i < bytes.size) {
        val byte = bytes[i].toInt()
        val octal = if (byte == BACKSLASH) octalAt(bytes, i + 1) else -1
        if (octal >= 0) {
            out.write(octal)
            i += 1 + OCTAL_DIGITS
        } else {
            out.write(byte)
            i += 1
        }
    }
    return out.toByteArray()
}

private fun octalAt(bytes: ByteArray, start: Int): Int {
    if (start + OCTAL_DIGITS > bytes.size) return -1
    var value = 0
    for (i in start until start + OCTAL_DIGITS) {
        val digit = bytes[i].toInt() - '0'.code
        if (digit !in 0 until OCTAL_RADIX) return -1
        value = value * OCTAL_RADIX + digit
    }
    return value and 0xff
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    for (i in from..haystack.size - needle.size) {
        if (needle.indices.all { haystack[i + it] == needle[it] }) return i
    }
    return -1
}

/** The number in a pane id such as `%12`. */
internal fun paneId(token: String): Int? = token.takeIf { it.startsWith('%') }?.substring(1)?.toIntOrNull()

/** The number in a window id such as `@3`. */
internal fun windowId(token: String): Int? = token.takeIf { it.startsWith('@') }?.substring(1)?.toIntOrNull()
