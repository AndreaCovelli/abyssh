package io.github.andreacovelli.abyssh.service.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Reads and sets how far the view is scrolled up from the bottom, through the native library. */
@RunWith(AndroidJUnit4::class)
class ViewportRowsAboveBottomTest {
    private val bridge = GhosttyBridge()
    private var handle = 0L

    @Before
    fun createTerminal() {
        handle = bridge.nativeCreate(COLUMNS, ROWS, SCROLLBACK_BYTES)
        val lines = (1..LINES).joinToString(separator = "\r\n") { "line $it" }
        bridge.nativeWriteRemote(handle, lines.toByteArray(Charsets.UTF_8))
    }

    @After
    fun destroyTerminal() {
        bridge.nativeDestroy(handle)
    }

    @Test
    fun aTerminalFollowingTheOutputIsAtTheBottom() {
        assertEquals(0, bridge.nativeViewportRowsAboveBottom(handle))
    }

    @Test
    fun scrollingUpIsReadBack() {
        bridge.nativeScrollToRowsAboveBottom(handle, 7)

        assertEquals(7, bridge.nativeViewportRowsAboveBottom(handle))
    }

    @Test
    fun scrollingPastTheHistoryStopsAtTheTop() {
        bridge.nativeScrollToRowsAboveBottom(handle, LINES * 2)

        assertEquals(LINES - ROWS, bridge.nativeViewportRowsAboveBottom(handle))
    }

    @Test
    fun zeroRowsReturnsToTheBottom() {
        bridge.nativeScrollToRowsAboveBottom(handle, 7)

        bridge.nativeScrollToRowsAboveBottom(handle, 0)

        assertEquals(0, bridge.nativeViewportRowsAboveBottom(handle))
    }

    private companion object {
        const val COLUMNS = 20
        const val ROWS = 5
        const val LINES = 40
        const val SCROLLBACK_BYTES = 1024 * 1024
    }
}
