package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlSupportTest {
    private val spec = TabSpec(
        hostId = 9_001,
        host = "example.test",
        username = "me",
        multiplexer = MultiplexerType.Tmux,
        tmuxControlMode = true,
    )

    @Test
    fun recordsWhetherTheHostsTmuxSupportsControlMode() {
        TmuxControlSupport.record(spec, "tmux 3.0a")
        assertTrue(TmuxControlSupport.isUnsupported(spec))

        TmuxControlSupport.record(spec, "tmux 3.4")
        assertFalse(TmuxControlSupport.isUnsupported(spec))
    }

    @Test
    fun controlModeNeedsTmuxOverSsh() {
        assertTrue(spec.usesTmuxControlMode)
        assertTrue(spec.copy(transport = Transport.TailscaleSSH).usesTmuxControlMode)
        assertFalse(spec.copy(tmuxControlMode = false).usesTmuxControlMode)
        assertFalse(spec.copy(multiplexer = MultiplexerType.Zmx).usesTmuxControlMode)
        assertFalse(spec.copy(transport = Transport.Mosh).usesTmuxControlMode)
    }
}
