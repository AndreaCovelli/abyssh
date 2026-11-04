package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxPaneBacklogTest {
    @Test
    fun awaitRoomReturnsAtOnceWithinTheLimit() = runTest {
        val backlog = TmuxPaneBacklog(limitBytes = 100)
        backlog.add(100)

        backlog.awaitRoom()

        assertEquals(100, backlog.bytes)
    }

    @Test
    fun awaitRoomWaitsUntilTheBacklogIsBackToHalf() = runTest {
        val backlog = TmuxPaneBacklog(limitBytes = 100)
        backlog.add(60)
        backlog.add(60)
        var resumed = false
        launch {
            backlog.awaitRoom()
            resumed = true
        }

        runCurrent()
        assertFalse(resumed)
        backlog.remove(60)
        runCurrent()
        assertFalse(resumed)
        backlog.remove(10)
        runCurrent()
        assertTrue(resumed)
        assertEquals(50, backlog.bytes)
    }
}
