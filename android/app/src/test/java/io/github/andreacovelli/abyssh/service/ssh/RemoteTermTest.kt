package io.github.andreacovelli.abyssh.service.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTermTest {
    @Test
    fun `known entry keeps xterm-ghostty`() {
        val output = "#\tReconstructed via infocmp from file: /home/u/.terminfo/x/xterm-ghostty\n" +
            "xterm-ghostty|Ghostty,\n\tTc,\n"
        assertEquals(RemoteTerm.GHOSTTY, RemoteTerm.fromProbeOutput(output, finished = true))
        // The entry is enough even before the channel closes.
        assertEquals(RemoteTerm.GHOSTTY, RemoteTerm.fromProbeOutput(output, finished = false))
    }

    @Test
    fun `missing entry or infocmp falls back to xterm-256color`() {
        val missing = "infocmp: couldn't open terminfo file (null).\n"
        assertEquals(RemoteTerm.FALLBACK, RemoteTerm.fromProbeOutput(missing, finished = true))
        val noInfocmp = "bash: line 1: infocmp: command not found\n"
        assertEquals(RemoteTerm.FALLBACK, RemoteTerm.fromProbeOutput(noInfocmp, finished = true))
    }

    @Test
    fun `unfinished probe without an entry is unknown`() {
        assertNull(RemoteTerm.fromProbeOutput("", finished = false))
    }
}
