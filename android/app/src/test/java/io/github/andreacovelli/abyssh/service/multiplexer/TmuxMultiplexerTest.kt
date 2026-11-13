package io.github.andreacovelli.abyssh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TmuxMultiplexerTest {
    @Test
    fun launchCreatesOrSwitchesOutsideTmuxAndSwitchesClientInsideTmux() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "abyssh-1",
            createIfMissing = true,
            trustedRemoteName = false,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=abyssh-1'; else exec tmux new-session -A -s 'abyssh-1'; fi",
            command,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun launchRejectsUngeneratedUntrustedNames() {
        TmuxMultiplexer.launchCommand(
            sessionName = "work'space",
            createIfMissing = true,
            trustedRemoteName = false,
        )
    }

    @Test
    fun launchQuotesTrustedRemoteNames() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "work'space; rm -rf /",
            createIfMissing = true,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=work'\\''space; rm -rf /'; else exec tmux new-session -A -s 'work'\\''space; rm -rf /'; fi",
            command,
        )
    }

    @Test
    fun launchExistingUsesSwitchClientAndExactAttachFallback() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "abyssh-1",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=abyssh-1'; " +
                "elif tmux has-session -t '=abyssh-1' 2>/dev/null; then exec tmux attach-session -t '=abyssh-1'; " +
                "else printf 'tmux session %s is no longer available\\n' 'abyssh-1'; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun launchExistingQuotesTrustedRemoteNamesWithMetacharacters() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "main tab; printf 'x'",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=main tab; printf '\\''x'\\'''; " +
                "elif tmux has-session -t '=main tab; printf '\\''x'\\''' 2>/dev/null; then exec tmux attach-session -t '=main tab; printf '\\''x'\\'''; " +
                "else printf 'tmux session %s is no longer available\\n' 'main tab; printf '\\''x'\\'''; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun listSessionsCommandChecksExecutableBeforeTreatingNoServerAsEmptySuccess() {
        val command = TmuxMultiplexer.listSessionsCommand()

        assertEquals(
            "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; (exit 127); " +
                "else tmux list-sessions -F '#{session_attached} #{session_name}' 2>/dev/null; " +
                "rc=\$?; if [ \"\$rc\" -eq 1 ]; then true; else [ \"\$rc\" -eq 0 ]; fi; fi",
            command,
        )
    }

    @Test
    fun listSessionsCommandDoesNotAssignZshReadOnlyStatus() {
        // zsh rejects `status=` as a read-only variable and stops before the exit marker.
        assertFalse(Regex("\\bstatus=").containsMatchIn(TmuxMultiplexer.listSessionsCommand()))
    }

    @Test
    fun parsesSessionListWithAttachedClientCountsAndNamesContainingSpaces() {
        val sessions = TmuxMultiplexer.parseSessions("0 work\n1 main\n2 work space\n")

        assertEquals(
            listOf(
                RemoteMultiplexerSession(name = "work", clients = 0),
                RemoteMultiplexerSession(name = "main", clients = 1),
                RemoteMultiplexerSession(name = "work space", clients = 2),
            ),
            sessions,
        )
    }

    @Test
    fun killSessionTargetsTheExactQuotedName() {
        assertEquals(
            "tmux kill-session -t '=work'\\''space'",
            TmuxMultiplexer.killSessionCommand("work'space"),
        )
    }

    @Test
    fun skipsMalformedSessionListLines() {
        val sessions = TmuxMultiplexer.parseSessions("missing-separator\n1 \n")

        assertEquals(
            emptyList<RemoteMultiplexerSession>(),
            sessions,
        )
    }
}
