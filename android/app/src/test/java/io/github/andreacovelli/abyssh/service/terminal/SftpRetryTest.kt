package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.service.ssh.SftpStatusException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SftpRetryTest {

    private val state = MutableStateFlow(SessionState(status = SessionStatus.Connected))
    private var calls = 0

    @Test
    fun `waits for the session to connect before the first call`() = runTest {
        state.value = SessionState(status = SessionStatus.Reconnecting)
        val result = async { runWhenConnected(state) { ++calls } }
        runCurrent()
        assertEquals(0, calls)

        state.value = SessionState(status = SessionStatus.Connected)

        assertEquals(1, result.await())
    }

    @Test
    fun `a drop during the call runs it again after the reconnect`() = runTest {
        val result = async {
            runWhenConnected(state) {
                calls++
                if (calls == 1) {
                    state.value = SessionState(status = SessionStatus.Reconnecting)
                    error("socket closed")
                }
                "listing"
            }
        }
        runCurrent()
        state.value = SessionState(status = SessionStatus.Connected)

        assertEquals("listing", result.await())
        assertEquals(2, calls)
    }

    @Test
    fun `a failure on a working connection is reported after the drop check`() = runTest {
        val result = async { runCatching { runWhenConnected(state) { calls++; error("stalled") } } }
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(SFTP_DROP_DETECTION_MS + 1)

        assertEquals("stalled", result.await().exceptionOrNull()?.message)
        assertEquals(1, calls)
    }

    @Test
    fun `a status from the server fails at once`() = runTest {
        val result = async {
            runCatching { runWhenConnected(state) { calls++; throw SftpStatusException("permission denied") } }
        }
        runCurrent()

        assertTrue(result.isCompleted)
        assertTrue(result.await().exceptionOrNull() is SftpStatusException)
        assertEquals(1, calls)
    }

    @Test
    fun `a session that ends in an error reports that error`() = runTest {
        val reason = UiText.Raw("Reconnect failed")
        state.value = SessionState(status = SessionStatus.Error, error = reason)

        val failure = runCatching { runWhenConnected(state) { calls++ } }.exceptionOrNull()

        assertEquals(reason, (failure as UiTextException).text)
        assertEquals(0, calls)
    }
}
