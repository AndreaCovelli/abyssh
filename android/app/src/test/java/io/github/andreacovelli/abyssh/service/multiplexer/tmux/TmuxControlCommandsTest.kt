package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlCommandsTest {
    @Test
    fun attachQuotesTheSessionName() {
        assertEquals("exec tmux -CC new-session -A -s 'it'\\''s'", TmuxControlCommands.attach("it's", createIfMissing = true))
        assertEquals("exec tmux -CC attach-session -t '=work'", TmuxControlCommands.attach("work", createIfMissing = false))
    }

    @Test
    fun switchClientQuotesTheSessionNameForTmux() {
        assertEquals("switch-client -t '=it'\\''s'", TmuxControlCommands.switchClient("it's"))
    }

    @Test
    fun windowCommandsTargetTheWindowId() {
        assertEquals("select-window -t @7", TmuxControlCommands.selectWindow(7))
        assertEquals("kill-window -t @7", TmuxControlCommands.killWindow(7))
    }

    @Test
    fun sendKeysSplitsLongInputIntoHexCommands() {
        val commands = TmuxControlCommands.sendKeys(4, ByteArray(300) { 0x1b })

        assertEquals(2, commands.size)
        assertTrue(commands[0].startsWith("send-keys -t %4 -H 1b 1b "))
        assertEquals(256, commands[0].removePrefix("send-keys -t %4 -H ").split(' ').size)
        assertEquals(44, commands[1].removePrefix("send-keys -t %4 -H ").split(' ').size)
        assertEquals(listOf("send-keys -t %0 -H 0d ff"), TmuxControlCommands.sendKeys(0, byteArrayOf(0x0d, -1)))
    }

    @Test
    fun parsesWindowsAndNewWindowReplies() {
        assertEquals(
            listOf(TmuxWindow(0, true, 0, TmuxPaneSize(40, 6)), TmuxWindow(3, false, 7, TmuxPaneSize(80, 23))),
            TmuxControlCommands.parseWindows(listOf("@0 1 %0 40 6", "@3 0 %7 80 23", "garbage")),
        )
        assertEquals(1, TmuxControlCommands.parseNewWindow(listOf("@1")))
        assertNull(TmuxControlCommands.parseNewWindow(emptyList()))
    }

    @Test
    fun acceptsTmux32AndLaterAndUnnumberedBuilds() {
        listOf("tmux 3.2", "tmux 3.2a", "tmux 3.7c", "tmux 4.0", "tmux next-3.4", "tmux master", "tmux openbsd-7.5", "", null)
            .forEach { assertTrue("$it", TmuxControlCommands.supportsControlMode(it)) }
        listOf("tmux 3.1c", "tmux 2.9a", "tmux 1.8")
            .forEach { assertFalse(it, TmuxControlCommands.supportsControlMode(it)) }
    }

    @Test
    fun splitsTheVersionLineFromTheSessionList() {
        assertEquals(
            "tmux 3.7c" to "0 work\n1 main",
            TmuxControlCommands.splitVersion("ABYSSH_TMUX_VERSION tmux 3.7c\n0 work\n1 main"),
        )
        assertEquals(null to "0 work", TmuxControlCommands.splitVersion("0 work"))
    }
}
