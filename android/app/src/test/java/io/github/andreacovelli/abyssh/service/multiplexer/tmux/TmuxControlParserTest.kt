package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlParserTest {
    private fun parse(vararg chunks: String): List<TmuxControlEvent> {
        val parser = TmuxControlParser()
        val events = mutableListOf<TmuxControlEvent>()
        chunks.forEach { parser.feed(it.toByteArray(Charsets.ISO_8859_1)) { event -> events += event } }
        return events
    }

    // Recorded from tmux 3.7c attaching with -CC, then running refresh-client and list-windows.
    private val attach =
        "\u001bP1000p%begin 1793448186 280 0\r\n" +
            "%end 1793448186 280 0\r\n" +
            "%window-add @0\r\n" +
            "%sessions-changed\r\n" +
            "%session-changed \$0 abyssh-1\r\n" +
            "%window-renamed @0 tmux\r\n" +
            "%output %0 \\033[?2004h\\033[01;32mandrea\\033[00m:~\$ \r\n" +
            "%begin 1793448186 290 1\r\n" +
            "%end 1793448186 290 1\r\n" +
            "%layout-change @0 f25d,40x6,0,0,0 f25d,40x6,0,0,0 *\r\n" +
            "%begin 1793448188 294 1\r\n" +
            "@0 1 %0 40 6\r\n" +
            "%end 1793448188 294 1\r\n"

    @Test
    fun skipsTheUnsolicitedAttachBlockAndReadsNotificationsAndReplies() {
        val events = parse(attach)

        assertEquals(TmuxControlEvent.WindowsChanged, events[0])
        assertEquals(TmuxControlEvent.SessionChanged(0, "abyssh-1"), events[1])
        val output = events[2] as TmuxControlEvent.Output
        assertEquals(0, output.paneId)
        assertEquals("\u001b[?2004h\u001b[01;32mandrea\u001b[00m:~\$ ", String(output.data, Charsets.UTF_8))
        assertEquals(TmuxControlEvent.Reply(emptyList(), failed = false), events[3])
        assertEquals(TmuxControlEvent.LayoutChanged(0, "f25d,40x6,0,0,0"), events[4])
        assertEquals(TmuxControlEvent.Reply(listOf("@0 1 %0 40 6"), failed = false), events[5])
        assertEquals(6, events.size)
    }

    @Test
    fun readsTheSameStreamSplitAtEveryByte() {
        val whole = parse(attach)
        val split = parse(*attach.map { it.toString() }.toTypedArray())

        assertEquals(whole.filterNot { it is TmuxControlEvent.Output }, split.filterNot { it is TmuxControlEvent.Output })
        assertArrayEquals(
            (whole[2] as TmuxControlEvent.Output).data,
            (split[2] as TmuxControlEvent.Output).data,
        )
    }

    @Test
    fun treatsPercentLinesInsideABlockAsOutput() {
        val events = parse(
            "%begin 1 7 1\r\n%0 60 %1 attached\r\n%end 1 6 1\r\n%end 1 7 1\r\n",
        )

        assertEquals(listOf(TmuxControlEvent.Reply(listOf("%0 60 %1 attached", "%end 1 6 1"), failed = false)), events)
    }

    @Test
    fun reportsFailedCommands() {
        val events = parse("%begin 1 9 1\r\ncan't find window: @7\r\n%error 1 9 1\r\n")

        assertEquals(listOf(TmuxControlEvent.Reply(listOf("can't find window: @7"), failed = true)), events)
    }

    @Test
    fun readsExtendedOutputAfterTheColon() {
        val events = parse("%extended-output %3 120 : echo hi\\015\\012\r\n")

        val output = events.single() as TmuxControlEvent.Output
        assertEquals(3, output.paneId)
        assertEquals("echo hi\r\n", String(output.data, Charsets.UTF_8))
    }

    @Test
    fun keepsRawUtf8BytesAndDecodesEscapedBackslashes() {
        val raw = byteArrayOf(0xe2.toByte(), 0x94.toByte())
        val parser = TmuxControlParser()
        val events = mutableListOf<TmuxControlEvent>()
        parser.feed("%output %1 a\\134b".toByteArray() + raw + "\n".toByteArray()) { events += it }

        assertArrayEquals("a\\b".toByteArray() + raw, (events.single() as TmuxControlEvent.Output).data)
    }

    @Test
    fun readsPauseWindowChangesAndExit() {
        val events = parse(
            "%pause %4\r\n%window-close @2\r\n%unlinked-window-close @1\r\n%window-pane-changed @0 %5\r\n" +
                "%session-changed \$3 work space\r\n%exit\r\n\u001b\\",
        )

        assertEquals(
            listOf(
                TmuxControlEvent.Pause(4),
                TmuxControlEvent.WindowsChanged,
                TmuxControlEvent.WindowsChanged,
                TmuxControlEvent.WindowsChanged,
                TmuxControlEvent.SessionChanged(3, "work space"),
                TmuxControlEvent.Exit(null),
            ),
            events,
        )
    }

    @Test
    fun ignoresShellOutputBeforeControlModeStarts() {
        val events = parse("Last login: today\r\nwelcome %user\r\n\u001bP1000p%exit server exited\r\n")

        assertEquals(listOf(TmuxControlEvent.Exit("server exited")), events)
    }

    @Test
    fun dropsLinesOverTheLimit() {
        val parser = TmuxControlParser()
        val events = mutableListOf<TmuxControlEvent>()
        val long = ByteArray(TMUX_MAX_LINE_BYTES + 10) { 'a'.code.toByte() }
        parser.feed("%output %1 ".toByteArray() + long) { events += it }
        parser.feed("\n%pause %1\n".toByteArray()) { events += it }

        assertEquals(listOf<TmuxControlEvent>(TmuxControlEvent.Pause(1)), events)
    }

    @Test
    fun leavesInvalidEscapesAlone() {
        assertArrayEquals("\\9x\\0".toByteArray(), unescapeOutput("\\9x\\0".toByteArray()))
        assertTrue(unescapeOutput("\\033".toByteArray()).contentEquals(byteArrayOf(0x1b)))
    }
}
