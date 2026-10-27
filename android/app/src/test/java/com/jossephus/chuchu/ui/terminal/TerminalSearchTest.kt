package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSearchTest {
    private val newest = SearchMatch(90, 0, 90, 2)
    private val middle = SearchMatch(50, 4, 50, 6)
    private val oldest = SearchMatch(10, 78, 11, 1)
    private val found = listOf(newest, middle, oldest)

    @Test
    fun `native results decode four ints per match`() {
        assertEquals(
            listOf(newest, oldest),
            parseSearchMatches(intArrayOf(90, 0, 90, 2, 10, 78, 11, 1)),
        )
        assertEquals(emptyList<SearchMatch>(), parseSearchMatches(IntArray(0)))
    }

    @Test
    fun `match on one visible row gives one span`() {
        assertEquals(
            listOf(SearchSpan(row = 5, firstCol = 4, lastCol = 6)),
            middle.viewportSpans(viewportTop = 45, rows = 24, cols = 80),
        )
    }

    @Test
    fun `wrapped match spans the end of one row and the start of the next`() {
        assertEquals(
            listOf(SearchSpan(0, 78, 79), SearchSpan(1, 0, 1)),
            oldest.viewportSpans(viewportTop = 10, rows = 24, cols = 80),
        )
        // Only the second half is on screen.
        assertEquals(
            listOf(SearchSpan(0, 0, 1)),
            oldest.viewportSpans(viewportTop = 11, rows = 24, cols = 80),
        )
    }

    @Test
    fun `match outside the viewport gives nothing`() {
        assertEquals(emptyList<SearchSpan>(), newest.viewportSpans(viewportTop = 0, rows = 24, cols = 80))
    }

    @Test
    fun `arrows walk the matches and wrap`() {
        val state = TerminalSearchState("x").withMatches(found)
        assertEquals(newest, state.currentMatch)
        assertEquals("3 of 3", state.counter)
        assertEquals(middle, state.older().currentMatch)
        assertEquals("2 of 3", state.older().counter)
        assertEquals(oldest, state.newer().currentMatch)
        assertEquals(newest, state.older().older().older().currentMatch)
    }

    @Test
    fun `refreshed results keep the current match`() {
        val onMiddle = TerminalSearchState("x").withMatches(found).older()
        val newOutput = SearchMatch(120, 0, 120, 2)
        val refreshed = onMiddle.withMatches(listOf(newOutput) + found)
        assertEquals(middle, refreshed.currentMatch)
        assertEquals("2 of 4", refreshed.counter)
    }

    @Test
    fun `no matches`() {
        val state = TerminalSearchState("x").withMatches(emptyList())
        assertNull(state.currentMatch)
        assertEquals("no matches", state.counter)
        assertEquals(state, state.older())
        assertEquals("", TerminalSearchState().counter)
    }
}
