package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadPacerTest {
    @Test
    fun idleWaitsLongerAsTheSessionGoesQuiet() = runTest {
        val pacer = ReadPacer { testScheduler.currentTime }
        val start = testScheduler.currentTime
        pacer.idle()
        assertEquals(2L, testScheduler.currentTime - start)

        advanceTimeBy(5_000)
        val quiet = testScheduler.currentTime
        pacer.idle()
        assertEquals(64L, testScheduler.currentTime - quiet)
    }

    @Test
    fun inputEndsAnIdleWaitAtOnce() = runTest {
        val pacer = ReadPacer { testScheduler.currentTime }
        advanceTimeBy(5_000)
        var woke = false
        launch {
            pacer.idle()
            woke = true
        }
        advanceTimeBy(10)
        runCurrent()
        assertFalse(woke)

        pacer.onInput()
        runCurrent()
        assertTrue(woke)
    }
}
