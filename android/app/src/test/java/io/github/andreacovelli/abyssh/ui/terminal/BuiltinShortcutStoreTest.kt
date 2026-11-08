package io.github.andreacovelli.abyssh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip and contract tests for [BuiltinShortcutStore].
 *
 * JSON storage means any key char survives — including the `:`, `,`, `;` that the
 * old inline delimited format could not (review finding F3). Pure Kotlin +
 * kotlinx.serialization, so this runs as a plain JUnit test.
 */
class BuiltinShortcutStoreTest {

    // Only the commands the test saved: parse gives the others their defaults.
    private fun roundTrip(m: Map<String, String>): Map<String, String> =
        BuiltinShortcutStore.parse(BuiltinShortcutStore.serialize(m)).filterKeys { it in m }

    @Test
    fun `keys with delimiter chars survive round-trip`() {
        val m = mapOf(
            "tabs" to ":",
            "new_tab" to ",",
            "actions" to ";",
            "settings" to "\"",
            "close" to "q",
        )
        assertEquals(BuiltinShortcutStore.normalize(m), roundTrip(m))
    }

    @Test
    fun `unknown command ids are dropped`() {
        val parsed = BuiltinShortcutStore.parse("{\"tabs\":\"t\",\"bogus\":\"x\"}")
        assertEquals("t", parsed["tabs"])
        assertEquals(BuiltinCommand.entries.map { it.id }.toSet(), parsed.keys)
    }

    @Test
    fun `commands added since the shortcuts were saved get their default keys when free`() {
        // Saved before the tmux commands existed, with split down's default key taken.
        val parsed = BuiltinShortcutStore.parse("{\"tabs\":\"t\",\"close\":\"b\"}")
        assertEquals("v", parsed[BuiltinCommand.SplitRight.id])
        assertEquals("", parsed[BuiltinCommand.SplitDown.id])
        assertEquals("b", parsed[BuiltinCommand.Close.id])
    }

    @Test
    fun `empty value is preserved as a hidden command`() {
        val m = mapOf("tabs" to "", "close" to "q")
        assertEquals(m, roundTrip(m))
    }

    @Test
    fun `multi-char value is normalized to its last char`() {
        assertEquals(mapOf("tabs" to "t"), BuiltinShortcutStore.normalize(mapOf("tabs" to "qt")))
    }

    @Test
    fun `uppercase key is normalized to lowercase`() {
        assertEquals(mapOf("tabs" to "t"), BuiltinShortcutStore.normalize(mapOf("tabs" to "T")))
    }

    @Test
    fun `surrounding whitespace is trimmed rather than kept as a space key`() {
        assertEquals(mapOf("tabs" to "t"), BuiltinShortcutStore.normalize(mapOf("tabs" to " t ")))
    }

    @Test
    fun `duplicate key is kept by the earlier command in enum order`() {
        // Tabs precedes Close, so Tabs keeps "t" and Close is blanked.
        assertEquals(
            mapOf("tabs" to "t", "close" to ""),
            BuiltinShortcutStore.normalize(mapOf("close" to "t", "tabs" to "t")),
        )
    }

    @Test
    fun `case-variant duplicate keys collide`() {
        assertEquals(
            mapOf("tabs" to "t", "close" to ""),
            BuiltinShortcutStore.normalize(mapOf("tabs" to "T", "close" to "t")),
        )
    }

    @Test
    fun `several commands may be hidden at once`() {
        assertEquals(
            mapOf("tabs" to "", "close" to ""),
            BuiltinShortcutStore.normalize(mapOf("tabs" to "", "close" to "")),
        )
    }

    @Test
    fun `null returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse(null))
    }

    @Test
    fun `blank returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse("   "))
    }

    @Test
    fun `malformed json returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse("not json {["))
    }
}
