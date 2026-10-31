package io.github.andreacovelli.abyssh.service.ssh

import io.github.andreacovelli.abyssh.service.terminal.WakeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCancelTest {

    private class RecordingWakeSignal : WakeSignal {
        private var next = 10
        val events = mutableListOf<String>()

        override fun create(): Int = next++.also { events += "create $it" }

        override fun signal(fd: Int) {
            events += "signal $fd"
        }

        override fun close(fd: Int) {
            events += "close $fd"
        }
    }

    private val wake = RecordingWakeSignal()
    private val cancellation = ConnectionCancel(wake)

    @Test
    fun `cancel signals the open descriptor`() {
        val fd = cancellation.open()

        cancellation.cancel()

        assertEquals(listOf("create $fd", "signal $fd"), wake.events)
        assertTrue(cancellation.requested)
    }

    @Test
    fun `cancel after close signals nothing`() {
        val fd = cancellation.open()
        cancellation.close()

        cancellation.cancel()

        assertEquals(listOf("create $fd", "close $fd"), wake.events)
    }

    @Test
    fun `a new connection closes the old descriptor and starts uncancelled`() {
        val first = cancellation.open()
        cancellation.cancel()

        val second = cancellation.open()

        assertEquals(listOf("create $first", "signal $first", "close $first", "create $second"), wake.events)
        assertFalse(cancellation.requested)
    }
}
