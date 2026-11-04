package io.github.andreacovelli.abyssh.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomActionGroupingTest {

    // Each entry is its key followed by a digit, such as "a1".
    private val key: (String) -> String = { it.take(1) }

    @Test
    fun `entries with the same key move to where the key first appears`() {
        assertEquals(listOf("a1", "a2", "b1", "c1"), listOf("a1", "b1", "a2", "c1").groupedByKey(key))
    }

    @Test
    fun `an entry swaps with a neighbour that has its key`() {
        assertEquals(listOf("a2", "a1", "b1"), listOf("a1", "a2", "b1").movedInGroups(0, 1, key))
    }

    @Test
    fun `an entry at a group edge moves its whole group past the next one`() {
        val items = listOf("a1", "a2", "b1", "b2", "c1")
        assertEquals(listOf("b1", "b2", "a1", "a2", "c1"), items.movedInGroups(1, 1, key))
        assertEquals(listOf("b1", "b2", "a1", "a2", "c1"), items.movedInGroups(2, -1, key))
    }

    @Test
    fun `nothing moves past either end`() {
        val items = listOf("a1", "b1")
        assertNull(items.movedInGroups(0, -1, key))
        assertNull(items.movedInGroups(1, 1, key))
        assertNull(items.movedInGroups(5, 1, key))
    }
}
