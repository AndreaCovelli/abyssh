package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxClientPanesTest {
    private fun pane(id: Int, x: Int, y: Int, cols: Int, rows: Int, dragSelects: Boolean = true) =
        TmuxClientPane(TmuxPaneRect(id, x, y, cols, rows), dragSelects)

    @Test
    fun readsPanesBelowNoStatusLineAndAboveABottomOne() {
        val output = """
            1 bottom
            %3 1 0 0 0 0 0 39 23
            %4 0 0 0 0 41 0 79 23
        """.trimIndent()

        assertEquals(listOf(pane(3, 0, 0, 40, 24), pane(4, 41, 0, 39, 24)), TmuxClientPanes.parse(output))
        assertEquals(listOf(pane(3, 0, 0, 40, 24)), TmuxClientPanes.parse("off top\n%3 1 0 0 0 0 0 39 23"))
    }

    @Test
    fun movesPanesBelowAStatusLineAtTheTop() {
        assertEquals(listOf(pane(3, 0, 1, 80, 23)), TmuxClientPanes.parse("on top\n%3 1 0 0 0 0 0 79 22"))
        assertEquals(listOf(pane(3, 0, 3, 80, 21)), TmuxClientPanes.parse("3 top\n%3 1 0 0 0 0 0 79 20"))
        assertEquals(listOf(pane(3, 0, 5, 80, 19)), TmuxClientPanes.parse("9 top\n%3 1 0 0 0 0 0 79 18"))
    }

    @Test
    fun keepsOnlyTheActivePaneOfAZoomedWindow() {
        val output = """
            1 bottom
            %3 0 1 0 0 0 0 79 23
            %4 1 1 0 0 0 0 79 23
        """.trimIndent()

        assertEquals(listOf(pane(4, 0, 0, 80, 24)), TmuxClientPanes.parse(output))
    }

    @Test
    fun aDragGoesToAProgramThatReadsTheMouseUnlessThePaneIsInCopyMode() {
        val output = """
            1 bottom
            %3 1 0 0 1 0 0 39 23
            %4 0 0 1 1 41 0 79 23
            %5 0 0 2 0 41 0 79 23
        """.trimIndent()

        assertEquals(listOf(false, true, true), TmuxClientPanes.parse(output)?.map { it.dragSelects })
    }

    @Test
    fun rejectsOutputItCannotRead() {
        assertNull(TmuxClientPanes.parse(""))
        assertNull(TmuxClientPanes.parse("1 bottom"))
        assertNull(TmuxClientPanes.parse("1 bottom\n%3 1 0 0 0 0 0 79"))
        assertNull(TmuxClientPanes.parse("1 bottom\n%3 1 0 0 0 0 0 79 23\nno server running"))
        assertNull(TmuxClientPanes.parse("1 bottom\n%3 1 0 0 0 10 0 9 23"))
    }

    @Test
    fun quotesTheSessionInTheCommand() {
        assertEquals(
            "tmux display-message -p -t '=it'\\''s:' '#{status} #{status-position}' \\; " +
                "list-panes -t '=it'\\''s:' -F '#{pane_id} #{pane_active} #{window_zoomed_flag} " +
                "#{pane_in_mode} #{mouse_any_flag} #{pane_left} #{pane_top} #{pane_right} #{pane_bottom}'",
            TmuxClientPanes.command("it's"),
        )
    }
}
