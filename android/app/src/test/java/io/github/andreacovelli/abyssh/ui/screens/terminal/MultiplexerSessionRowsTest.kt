package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionRowsTest {
    @Test
    fun theActiveTabsOwnClientDoesNotCountAsElsewhere() {
        val rows = multiplexerSessionRows(
            listOf(
                RemoteMultiplexerSession("abyssh-1", clients = 1),
                RemoteMultiplexerSession("runner", clients = 2),
            ),
            currentName = "abyssh-1",
        )

        assertEquals(
            listOf(
                MultiplexerSessionRowState("abyssh-1", current = true, inUseElsewhere = false),
                MultiplexerSessionRowState("runner", current = false, inUseElsewhere = true),
            ),
            rows,
        )
    }

    @Test
    fun anotherClientOnTheCurrentSessionIsInUseElsewhere() {
        val rows = multiplexerSessionRows(listOf(RemoteMultiplexerSession("main", clients = 2)), currentName = "main")

        assertEquals(listOf(MultiplexerSessionRowState("main", current = true, inUseElsewhere = true)), rows)
    }

    @Test
    fun aDetachedSessionIsNeitherCurrentNorInUse() {
        val rows = multiplexerSessionRows(listOf(RemoteMultiplexerSession("old", clients = 0)), currentName = null)

        assertEquals(listOf(MultiplexerSessionRowState("old", current = false, inUseElsewhere = false)), rows)
    }
}
