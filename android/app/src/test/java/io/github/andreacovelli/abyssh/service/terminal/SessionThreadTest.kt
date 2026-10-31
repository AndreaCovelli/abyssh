package io.github.andreacovelli.abyssh.service.terminal

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionThreadTest {
    /** A wake descriptor backed by a semaphore, so a fake wait can block on it. */
    private class FakeWakeSignal(private val fd: Int = 7) : WakeSignal {
        val wakes = Semaphore(0)
        val closed = CopyOnWriteArrayList<Int>()

        override fun create(): Int = fd

        override fun signal(fd: Int) = wakes.release()

        override fun close(fd: Int) {
            closed += fd
        }

        /** Blocks like the native wait: until signalled, or for [timeoutMs]. */
        fun wait(timeoutMs: Long): Int =
            if (wakes.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) NativeWait.WOKEN else NativeWait.TIMEOUT
    }

    @Test
    fun dispatchedWorkWakesABlockedWait() = runBlocking {
        val signal = FakeWakeSignal()
        val thread = SessionThread(signal)
        val scope = CoroutineScope(thread.dispatcher)
        val waiting = CountDownLatch(1)
        val waitResult =
            scope.async {
                thread.awaitOutput {
                    waiting.countDown()
                    signal.wait(timeoutMs = 10_000)
                }
            }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))

        val ran = CompletableDeferred<Unit>()
        scope.launch { ran.complete(Unit) }

        withTimeout(5_000) {
            assertTrue(waitResult.await())
            ran.await()
        }
        thread.close()
    }

    @Test
    fun queuedWorkRunsInsteadOfWaiting() = runBlocking {
        val signal = FakeWakeSignal()
        val thread = SessionThread(signal)
        val scope = CoroutineScope(thread.dispatcher)
        val order = CopyOnWriteArrayList<String>()
        var waited = false
        val loop =
            scope.async {
                launch { order += "queued" }
                val result =
                    thread.awaitOutput {
                        waited = true
                        NativeWait.TIMEOUT
                    }
                order += "after wait"
                result
            }
        withTimeout(5_000) { assertTrue(loop.await()) }
        assertFalse(waited)
        assertEquals(listOf("queued", "after wait"), order)
        thread.close()
    }

    @Test
    fun aWaitThatCannotBlockFallsBackToPolling() = runBlocking {
        val thread = SessionThread(FakeWakeSignal())
        val result =
            CoroutineScope(thread.dispatcher).async { thread.awaitOutput { NativeWait.CANNOT_WAIT } }
        withTimeout(5_000) { assertFalse(result.await()) }
        thread.close()
    }

    @Test
    fun noWakeDescriptorFallsBackToPolling() = runBlocking {
        val thread = SessionThread(FakeWakeSignal(fd = -1))
        var waited = false
        val result =
            CoroutineScope(thread.dispatcher).async {
                thread.awaitOutput {
                    waited = true
                    NativeWait.READY
                }
            }
        withTimeout(5_000) { assertFalse(result.await()) }
        assertFalse(waited)
        thread.close()
    }

    @Test
    fun closeClosesTheWakeDescriptorOnce() {
        val signal = FakeWakeSignal(fd = 9)
        val thread = SessionThread(signal)
        thread.close()
        thread.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (signal.closed.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(listOf(9), signal.closed)
    }
}
