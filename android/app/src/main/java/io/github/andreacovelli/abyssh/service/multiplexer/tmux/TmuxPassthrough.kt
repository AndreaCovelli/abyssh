package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import java.io.ByteArrayOutputStream

/**
 * Unwraps tmux passthrough (`ESC P tmux; … ESC \`) in one pane's output. A client attached
 * normally gets only the inner sequence, with each doubled ESC halved, but control mode
 * sends the pane's output as the program wrote it, wrapper included. Programs inside tmux
 * send Kitty graphics this way (render-latex.nvim, snacks.nvim), so without this the
 * pane's terminal drops them as an unknown DCS. A wrapper can span several `%output`
 * lines, so the state carries over from one call to the next.
 */
internal class TmuxPassthrough {
    // Bytes of PREFIX seen so far, held back until they turn out to start a wrapper or not.
    private var matched = 0
    private var inside = false

    // Inside a wrapper, the last byte was an ESC: the next one doubles it or ends the wrapper.
    private var escape = false

    fun unwrap(data: ByteArray): ByteArray {
        if (!inside && matched == 0 && data.indexOf(ESC) < 0) return data
        val out = ByteArrayOutputStream(data.size)
        for (byte in data) {
            if (inside) inner(byte, out) else outer(byte, out)
        }
        return out.toByteArray()
    }

    /** Forgets a wrapper in progress, for a pane whose terminal starts over. */
    fun reset() {
        matched = 0
        inside = false
        escape = false
    }

    private fun outer(byte: Byte, out: ByteArrayOutputStream) {
        if (byte == PREFIX[matched]) {
            matched++
            if (matched == PREFIX.size) {
                matched = 0
                inside = true
            }
            return
        }
        if (matched > 0) {
            out.write(PREFIX, 0, matched)
            matched = 0
            if (byte == ESC) {
                matched = 1
                return
            }
        }
        out.write(byte.toInt())
    }

    private fun inner(byte: Byte, out: ByteArrayOutputStream) {
        if (!escape) {
            if (byte == ESC) escape = true else out.write(byte.toInt())
            return
        }
        escape = false
        when (byte) {
            BACKSLASH -> inside = false
            ESC -> out.write(ESC.toInt())
            else -> {
                out.write(ESC.toInt())
                out.write(byte.toInt())
            }
        }
    }

    private companion object {
        const val ESC: Byte = 0x1b
        const val BACKSLASH = '\\'.code.toByte()
        val PREFIX = "\u001bPtmux;".toByteArray(Charsets.US_ASCII)
    }
}
