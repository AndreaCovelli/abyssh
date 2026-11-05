package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HostConnectionsTest {
    private suspend fun recorded(vararg states: Set<Long>): List<Long> {
        val hosts = mutableListOf<Long>()
        recordNewConnections(flowOf(*states)) { hosts += it }
        return hosts
    }

    @Test
    fun `a host is recorded when its first tab connects`() = runTest {
        assertEquals(listOf(1L), recorded(emptySet(), setOf(1)))
    }

    @Test
    fun `another tab or a second host only records what's new`() = runTest {
        assertEquals(listOf(1L, 2L), recorded(setOf(1), setOf(1), setOf(1, 2)))
    }

    @Test
    fun `a host is recorded again after it has fully disconnected`() = runTest {
        assertEquals(listOf(1L, 1L), recorded(setOf(1), emptySet(), setOf(1)))
    }
}
