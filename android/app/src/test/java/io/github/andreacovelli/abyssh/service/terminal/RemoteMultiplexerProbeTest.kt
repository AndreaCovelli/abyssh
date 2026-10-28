package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerAvailability
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMultiplexerProbeTest {
    @Test
    fun exitEnvelopePrintsTheExitCodeAfterTheCommand() {
        assertEquals("tmux ls; printf '\nABYSSH_EXIT:%s\n' \"\$?\"", withExitEnvelope("tmux ls"))
    }

    @Test
    fun envelopeParsingSplitsOutputFromTheExitCode() {
        assertEquals(
            MultiplexerCommandResult(exitCode = 0, stdout = "main: 1 windows", stderr = ""),
            parseCommandEnvelope("main: 1 windows\n\nABYSSH_EXIT:0\n"),
        )
        assertEquals(1, parseCommandEnvelope("ABYSSH_EXIT:1").exitCode)
    }

    @Test
    fun envelopeParsingReportsAMissingMarker() {
        val result = parseCommandEnvelope("partial output")
        assertEquals(125, result.exitCode)
        assertEquals("partial output", result.stdout)
        assertEquals("Missing command exit marker", result.stderr)
    }

    @Test
    fun onlySshTransportsRunRemoteCommands() {
        assertTrue(Transport.SSH.runsRemoteCommands)
        assertTrue(Transport.TailscaleSSH.runsRemoteCommands)
        assertFalse(Transport.Mosh.runsRemoteCommands)
        assertFalse(Transport.LocalShell.runsRemoteCommands)
    }

    @Test
    fun availabilityErrorMessages() {
        assertNull(MultiplexerAvailability.Available.errorMessage(MultiplexerType.Tmux))
        assertEquals(
            "tmux executable was not found on the remote host",
            MultiplexerAvailability.Missing(MultiplexerType.Tmux).errorMessage(MultiplexerType.Tmux),
        )
        assertEquals(
            "zmx is not supported for Mosh",
            MultiplexerAvailability.UnsupportedTransport(Transport.Mosh).errorMessage(MultiplexerType.Zmx),
        )
        assertEquals(
            "Multiplexer is not supported for Mosh",
            MultiplexerAvailability.UnsupportedTransport(Transport.Mosh).errorMessage(null),
        )
        assertEquals("boom", MultiplexerAvailability.Error("boom").errorMessage(MultiplexerType.Tmux))
    }

    @Test
    fun startupCommandNeedsASupportedMultiplexerAndASessionName() {
        val spec = TabSpec(multiplexer = MultiplexerType.Tmux, multiplexerSessionName = "abyssh-1")
        assertTrue(spec.multiplexerStartupCommand().orEmpty().contains("abyssh-1"))
        assertNull(spec.copy(multiplexerSessionName = " ").multiplexerStartupCommand())
        assertNull(spec.copy(multiplexer = null).multiplexerStartupCommand())
        assertNull(spec.copy(multiplexer = MultiplexerType.Zellij).multiplexerStartupCommand())
        assertNull(spec.copy(transport = Transport.Mosh).multiplexerStartupCommand())
    }
}
