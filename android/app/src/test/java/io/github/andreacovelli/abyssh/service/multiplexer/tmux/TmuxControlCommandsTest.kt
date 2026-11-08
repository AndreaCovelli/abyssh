package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlCommandsTest {
    @Test
    fun attachQuotesTheSessionName() {
        assertEquals("exec env TERM=xterm-ghostty tmux -CC new-session -A -s 'it'\\''s'", TmuxControlCommands.attach("it's", createIfMissing = true))
        assertEquals("exec env TERM=xterm-ghostty tmux -CC attach-session -t '=work'", TmuxControlCommands.attach("work", createIfMissing = false))
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
    fun paneFlowCommandsPauseAndContinueThePane() {
        // `pause`, unlike `off`, keeps tmux reading the pane, so its program doesn't stall.
        assertEquals("refresh-client -A '%4:pause'", TmuxControlCommands.pausePane(4))
        assertEquals("refresh-client -A '%4:continue'", TmuxControlCommands.continuePane(4))
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
    fun sendKeysToActivePaneHasNoTarget() {
        assertEquals(listOf("send-keys -H 6c 73 0a"), TmuxControlCommands.sendKeysToActivePane("ls\n".toByteArray()))
    }

    @Test
    fun parsesWindowsAndNewWindowReplies() {
        assertEquals(
            listOf(
                TmuxWindow(4, false, 9, TmuxWindowLayout(81, 23, listOf(TmuxPaneRect(8, 0, 0, 40, 23), TmuxPaneRect(9, 41, 0, 40, 23))), 0, "vim"),
                TmuxWindow(0, true, 0, TmuxWindowLayout.single(0, TmuxPaneSize(40, 6)), 1, ""),
                TmuxWindow(3, false, 7, TmuxWindowLayout.single(7, TmuxPaneSize(80, 23)), 2, "build logs"),
            ),
            TmuxControlCommands.parseWindows(
                listOf(
                    "@0 1 1 %0 40 6 broken ",
                    "@3 2 0 %7 80 23 broken build logs",
                    "@4 0 0 %9 40 23 b25d,81x23,0,0{40x23,0,0,8,40x23,41,0,9} vim",
                    "garbage",
                ),
            ),
        )
        assertEquals(1, TmuxControlCommands.parseNewWindow(listOf("@1")))
        assertNull(TmuxControlCommands.parseNewWindow(emptyList()))
    }

    @Test
    fun aWindowIsLabelledAsTmuxsStatusLineShowsIt() {
        assertEquals("2:build logs", TmuxWindow(3, false, 7, TmuxWindowLayout.single(7, TmuxPaneSize(80, 23)), 2, "build logs").label)
    }

    @Test
    fun renamingAWindowQuotesTheNameAndKeepsTmuxFromExpandingIt() {
        assertEquals("rename-window -t @2 'it'\\''s ##1 now'", TmuxControlCommands.renameWindow(2, " it's #1\nnow "))
    }

    @Test
    fun paneCommandsNameThePane() {
        assertEquals("split-window -h -t %3", TmuxControlCommands.splitPane(3, sideBySide = true))
        assertEquals("split-window -v -t %3", TmuxControlCommands.splitPane(3, sideBySide = false))
        assertEquals("kill-pane -t %3", TmuxControlCommands.killPane(3))
        assertEquals("resize-pane -Z -t %3", TmuxControlCommands.toggleZoom(3))
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
