package io.github.andreacovelli.abyssh.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalCanvasGestureTest {
    @get:Rule val compose = createComposeRule()

    private var cellWidth = 0
    private var cellHeight = 0
    private val linkTaps = mutableListOf<String?>()
    private val clicks = mutableListOf<Offset>()
    private val scrolls = mutableListOf<Int>()

    private fun snapshot(appTracksMouse: Boolean, vararg rows: String): TerminalSnapshot {
        val cols = 40
        val text = rows.joinToString("") { it.padEnd(cols) }
        return TerminalSnapshot.fromCells(
            cols = cols,
            rows = rows.size,
            cursorX = 0,
            cursorY = rows.size - 1,
            cursorVisible = false,
            defaultBgArgb = 0xFF000000.toInt(),
            defaultFgArgb = 0xFFFFFFFF.toInt(),
            codepoints = IntArray(text.length) { text[it].code },
            fgArgb = IntArray(text.length) { 0xFFFFFFFF.toInt() },
            bgArgb = IntArray(text.length) { 0xFF000000.toInt() },
            flags = ByteArray(text.length),
            appTracksMouse = appTracksMouse,
        )
    }

    private fun show(snapshot: TerminalSnapshot) {
        compose.setContent {
            TerminalCanvas(
                snapshot = { snapshot },
                modifier = Modifier.size(360.dp, 200.dp).testTag("canvas"),
                onResize = { _, _, w, h, _, _ -> cellWidth = w; cellHeight = h },
                onLinkTap = { _, _, url, noLink -> if (url == null) noLink() else linkTaps += url },
                onPrimaryClick = { x, y -> clicks += Offset(x, y) },
                onScroll = { delta, _, _ -> scrolls += delta },
            )
        }
        compose.waitUntil { cellWidth > 0 && cellHeight > 0 }
    }

    private fun cell(col: Int, row: Int) = Offset((col + 0.5f) * cellWidth, (row + 0.5f) * cellHeight)

    @Test
    fun tapOnUrlReportsTheLinkAndElsewhereClicks() {
        show(snapshot(appTracksMouse = false, "https://example.com/x", "plain text"))
        compose.onNodeWithTag("canvas").performTouchInput { click(cell(3, 0)) }
        compose.runOnIdle {
            assertEquals(listOf<String?>("https://example.com/x"), linkTaps)
            assertTrue(clicks.isEmpty())
        }

        // Wait out the double-tap window so this is a tap of its own.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("canvas").performTouchInput { click(cell(2, 1)) }
        compose.runOnIdle {
            assertEquals(1, linkTaps.size)
            assertEquals(1, clicks.size)
        }
    }

    @Test
    fun tapGoesToTheAppWhenItTracksTheMouse() {
        show(snapshot(appTracksMouse = true, "https://example.com/x"))
        compose.onNodeWithTag("canvas").performTouchInput { click(cell(3, 0)) }
        compose.runOnIdle {
            assertTrue(linkTaps.isEmpty())
            assertEquals(1, clicks.size)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mouseWheelScrollsThreeRowsPerNotch() {
        show(snapshot(appTracksMouse = false, "a", "b", "c"))
        compose.onNodeWithTag("canvas").performMouseInput {
            moveTo(cell(1, 1))
            scroll(1f)
        }
        compose.waitUntil { scrolls.isNotEmpty() }
        assertEquals(3, kotlin.math.abs(scrolls.sum()))
    }
}
