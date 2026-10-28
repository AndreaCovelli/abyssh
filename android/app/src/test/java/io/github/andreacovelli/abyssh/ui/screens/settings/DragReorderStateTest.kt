package io.github.andreacovelli.abyssh.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragReorderStateTest {

    @Test
    fun `drag moves one slot per full step and keeps the remainder as offset`() {
        val state = DragReorderState<String>(stepPx = 10f)
        val moves = mutableListOf<Int>()
        state.start("a")

        state.drag(25f) { direction ->
            moves += direction
            true
        }

        assertEquals(listOf(1, 1), moves)
        assertEquals(5f, state.offsetFor("a"))
        assertEquals(0f, state.offsetFor("b"))
    }

    @Test
    fun `a refused move stops stepping and leaves the offset with the pointer`() {
        val state = DragReorderState<String>(stepPx = 10f)
        var calls = 0
        state.start("a")

        state.drag(-30f) {
            calls++
            false
        }

        assertEquals(1, calls)
        assertEquals(-30f, state.offsetPx)
    }

    @Test
    fun `drag without a started item does nothing`() {
        val state = DragReorderState<String>(stepPx = 10f)
        state.drag(50f) { error("no item is being dragged") }
        assertNull(state.draggingKey)
        assertEquals(0f, state.offsetPx)
    }

    @Test
    fun `stop clears the dragged item and offset`() {
        val state = DragReorderState<String>(stepPx = 10f)
        state.start("a")
        state.drag(4f) { true }
        state.stop()
        assertNull(state.draggingKey)
        assertEquals(0f, state.offsetFor("a"))
    }

    @Test
    fun `moved returns a reordered copy or null when nothing moves`() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), list.moved(0, 2))
        assertEquals(listOf("b", "a", "c"), list.moved(1, 0))
        assertNull(list.moved(1, 1))
        assertNull(list.moved(-1, 0))
        assertNull(list.moved(2, 3))
    }
}
