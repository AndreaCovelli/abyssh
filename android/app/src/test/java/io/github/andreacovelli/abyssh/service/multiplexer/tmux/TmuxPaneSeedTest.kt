package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxPaneSeedTest {
    private val esc = "\u001b["

    @Test
    fun parsesTheDisplayFormat() {
        // Recorded from tmux 3.7c for a 40x5 pane running bash.
        val state = TmuxPaneState.parse("40 5 32 0 0 4294967295 4294967295 1 0 0 0 0 0 0 0 0 1 0 0 4 0 1")

        assertEquals(
            TmuxPaneState(size = TmuxPaneSize(40, 5), cursorX = 32, bracketedPaste = true),
            state,
        )
        assertNull(TmuxPaneState.parse("40 5"))
        assertEquals(22, TmuxPaneState.FORMAT.split(' ').size)
    }

    @Test
    fun readsMissingFieldsFromOlderTmuxAsOff() {
        val state = TmuxPaneState.parse("80 24 0 0 0 0 0 1 0 0 0 0  0 0 0 1 0 0 23 10 ")

        assertEquals(false, state?.mouseAll)
        assertEquals(false, state?.bracketedPaste)
        assertEquals(10, state?.historySize)
    }

    @Test
    fun pushesHistoryIntoTheScrollbackThenDrawsTheScreen() {
        val state = TmuxPaneState(size = TmuxPaneSize(10, 3), cursorX = 2, cursorY = 1, historySize = 2)

        val seed = String(TmuxPaneSeed.build(state, listOf("old 1", "old 2"), emptyList(), listOf("a", "", "c")), Charsets.UTF_8)

        assertEquals(
            "${esc}0mold 1\r\nold 2\r\n${esc}0m\n\n" +
                "${esc}0m${esc}1;1Ha${esc}3;1Hc${esc}0m" +
                "${esc}2;3H",
            seed,
        )
    }

    @Test
    fun skipsTheHistoryCaptureWhenThePaneHasNone() {
        val state = TmuxPaneState(size = TmuxPaneSize(10, 2))

        val seed = String(TmuxPaneSeed.build(state, listOf("first screen line"), emptyList(), listOf("x")), Charsets.UTF_8)

        assertEquals("${esc}0m${esc}1;1Hx${esc}0m${esc}1;1H", seed)
    }

    @Test
    fun drawsTheNormalScreenBehindTheAlternateOneAndRestoresModes() {
        val state = TmuxPaneState(
            size = TmuxPaneSize(10, 4),
            cursorX = 0,
            cursorY = 3,
            alternateOn = true,
            alternateSavedX = 4,
            alternateSavedY = 1,
            cursorVisible = false,
            keypadCursor = true,
            mouseButton = true,
            mouseSgr = true,
            origin = true,
            scrollRegionUpper = 1,
            scrollRegionLower = 3,
            wrap = false,
        )

        val seed = String(TmuxPaneSeed.build(state, emptyList(), listOf("\$ vim"), listOf("~")), Charsets.UTF_8)

        assertEquals(
            "${esc}0m${esc}1;1H\$ vim${esc}0m${esc}2;5H$esc?1049h" +
                "${esc}0m${esc}1;1H~${esc}0m" +
                "${esc}2;4r$esc?1h$esc?6h$esc?1002h$esc?1006h$esc?7l${esc}3;1H$esc?25l",
            seed,
        )
    }
}
