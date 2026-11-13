package io.github.andreacovelli.abyssh.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenOutputPauseTest {
    private class Channel {
        var open = true
        val calls = mutableListOf<Boolean>()
        val pause = HiddenOutputPause { paused ->
            calls += paused
            open
        }
    }

    private fun enabledWithShell(): Channel = Channel().apply {
        pause.setEnabled(true)
        pause.onShellOpened()
    }

    @Test
    fun aQuietTabPausesOnlyWhenTheSettingIsOn() {
        val c = Channel()
        c.pause.onShellOpened()
        c.pause.setQuiet(true)
        assertEquals(emptyList<Boolean>(), c.calls)
        assertFalse(c.pause.paused)

        c.pause.setEnabled(true)
        assertEquals(listOf(true), c.calls)
        assertTrue(c.pause.paused)
    }

    @Test
    fun showingTheTabResumes() {
        val c = enabledWithShell()
        c.pause.setQuiet(true)
        c.pause.setQuiet(false)

        assertEquals(listOf(true, false), c.calls)
        assertFalse(c.pause.paused)
    }

    @Test
    fun turningTheSettingOffResumesAHiddenTab() {
        val c = enabledWithShell()
        c.pause.setQuiet(true)
        c.pause.setEnabled(false)

        assertEquals(listOf(true, false), c.calls)
        assertFalse(c.pause.paused)
    }

    @Test
    fun repeatedQuietCallsChangeNothing() {
        val c = enabledWithShell()
        c.pause.setQuiet(true)
        c.pause.setQuiet(true)
        c.pause.setEnabled(true)

        assertEquals(listOf(true), c.calls)
    }

    @Test
    fun nothingPausesWithoutAShell() {
        val c = Channel()
        c.pause.setEnabled(true)
        c.pause.setQuiet(true)

        assertEquals(emptyList<Boolean>(), c.calls)
        assertFalse(c.pause.paused)
    }

    @Test
    fun aShellThatOpensWhileTheTabIsQuietPausesAtOnce() {
        val c = Channel()
        c.pause.setEnabled(true)
        c.pause.setQuiet(true)
        c.pause.onShellOpened()

        assertEquals(listOf(true), c.calls)
        assertTrue(c.pause.paused)
    }

    @Test
    fun aReconnectWhileQuietPausesTheNewChannel() {
        val c = enabledWithShell()
        c.pause.setQuiet(true)
        // The old channel took its pause with it; the new one starts running.
        c.pause.onShellClosed()
        assertFalse(c.pause.paused)
        c.pause.onShellOpened()

        assertEquals(listOf(true, true), c.calls)
        assertTrue(c.pause.paused)
    }

    @Test
    fun aClosedShellIsNotResumedWhenTheTabIsShown() {
        val c = enabledWithShell()
        c.pause.setQuiet(true)
        c.pause.onShellClosed()
        c.pause.setQuiet(false)

        assertEquals(listOf(true), c.calls)
    }

    @Test
    fun aPauseTheChannelRefusedIsTriedAgainOnTheNextChange() {
        val c = Channel()
        c.open = false
        c.pause.setEnabled(true)
        c.pause.onShellOpened()
        c.pause.setQuiet(true)
        assertFalse(c.pause.paused)

        c.open = true
        c.pause.setEnabled(true)
        assertEquals(listOf(true, true), c.calls)
        assertTrue(c.pause.paused)
    }
}
