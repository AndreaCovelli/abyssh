package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimingTest {
    @Test
    fun reconnectDelayDoublesUpToThirtyTwoSeconds() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 32_000L, 32_000L),
            (1..8).map(::reconnectDelayMs),
        )
    }

    @Test
    fun readDelayBacksOffAsTheSessionGoesIdle() {
        assertEquals(2L, idleReadDelayMs(0))
        assertEquals(2L, idleReadDelayMs(49))
        assertEquals(8L, idleReadDelayMs(50))
        assertEquals(24L, idleReadDelayMs(500))
        assertEquals(64L, idleReadDelayMs(3_000))
    }

    @Test
    fun ptySizeIsTheGridExtentOnceCellsAreKnown() {
        val geometry = TerminalGeometry(cols = 80, rows = 24, cellWidth = 10, cellHeight = 20, screenWidth = 805)
        assertEquals(800, geometry.ptyWidthPx)
        assertEquals(480, geometry.ptyHeightPx)
        val unknownCells = geometry.copy(cellWidth = 0, cellHeight = 0, screenHeight = 490)
        assertEquals(805, unknownCells.ptyWidthPx)
        assertEquals(490, unknownCells.ptyHeightPx)
    }

    @Test
    fun notificationLabelNamesTheActiveTabAndTheCount() {
        val shell = TabSpec(transport = Transport.LocalShell)
        val server = TabSpec(host = "example.com", username = "me")
        val localShell = UiText.Resource(R.string.session_notification_local_shell)
        val twoSessions = UiText.Plural(R.plurals.session_notification_sessions, 2)
        assertEquals(UiText.Resource(R.string.session_notification_fallback), sessionNotificationLabel(emptyList(), null))
        assertEquals(localShell, sessionNotificationLabel(listOf(shell), null))
        assertEquals(
            UiText.Resource(R.string.session_notification_count, twoSessions, UiText.Raw("me@example.com:22")),
            sessionNotificationLabel(listOf(shell, server), server),
        )
        assertEquals(
            UiText.Resource(R.string.session_notification_count, twoSessions, localShell),
            sessionNotificationLabel(listOf(shell, server), null),
        )
    }
}
