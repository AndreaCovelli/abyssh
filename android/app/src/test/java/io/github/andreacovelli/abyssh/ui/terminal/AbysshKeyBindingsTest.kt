package io.github.andreacovelli.abyssh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbysshKeyBindingsTest {
    private val ran = mutableListOf<Char>()
    private val keys = AbysshKeyBindings(
        hints = emptyList(),
        handlers = mapOf('t' to { ran += 't' }),
    )

    @Test
    fun `runShortcut runs the bound command without the prefix`() {
        assertTrue(keys.hasShortcut('t'))
        assertTrue(keys.runShortcut('t'))
        assertEquals(listOf('t'), ran)
    }

    @Test
    fun `runShortcut matches keys case-insensitively`() {
        assertTrue(keys.runShortcut('T'))
        assertEquals(listOf('t'), ran)
    }

    @Test
    fun `runShortcut leaves unbound keys alone`() {
        assertFalse(keys.hasShortcut('x'))
        assertFalse(keys.runShortcut('x'))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `runShortcut clears a pending prefix`() {
        keys.togglePrefix()
        keys.runShortcut('t')
        assertFalse(keys.isPrefixActive)
    }
}
