package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxLayoutTest {
    @Test
    fun readsASinglePane() {
        assertEquals(
            TmuxWindowLayout(40, 6, listOf(TmuxPaneRect(0, 0, 0, 40, 6))),
            TmuxLayout.parse("f25d,40x6,0,0,0"),
        )
    }

    @Test
    fun readsNestedSplits() {
        assertEquals(
            TmuxWindowLayout(
                100,
                30,
                listOf(
                    TmuxPaneRect(203, 0, 0, 50, 30),
                    TmuxPaneRect(204, 51, 0, 49, 15),
                    TmuxPaneRect(205, 51, 16, 49, 14),
                ),
            ),
            TmuxLayout.parse("7c9f,100x30,0,0{50x30,0,0,203,49x30,51,0[49x15,51,0,204,49x14,51,16,205]}"),
        )
    }

    @Test
    fun findsThePaneUnderACell() {
        val layout = TmuxLayout.parse("7c9f,100x30,0,0{50x30,0,0,203,49x30,51,0[49x15,51,0,204,49x14,51,16,205]}")!!

        assertEquals(203, layout.paneAt(49, 29)?.paneId)
        assertNull(layout.paneAt(50, 3))
        assertEquals(204, layout.paneAt(51, 14)?.paneId)
        assertNull(layout.paneAt(60, 15))
        assertEquals(205, layout.paneAt(99, 16)?.paneId)
    }

    @Test
    fun rejectsBrokenLayouts() {
        assertNull(TmuxLayout.parse("eb8f,100x30,0,0{50x30,0,0,1"))
        assertNull(TmuxLayout.parse("eb8f,100x30,0,0{50x30,0,0,1,49x30,51,0,2}x"))
        assertNull(TmuxLayout.parse("nonsense"))
        assertNull(TmuxLayout.parse(""))
    }

    @Test
    fun rejectsLayoutsNestedTooDeep() {
        fun nested(depth: Int): String = "10x10,0,0{".repeat(depth) + "10x10,0,0,1" + "}".repeat(depth)

        assertEquals(1, TmuxLayout.parse("abcd,${nested(64)}")?.panes?.single()?.paneId)
        assertNull(TmuxLayout.parse("abcd,${nested(65)}"))
        assertNull(TmuxLayout.parse("abcd,${nested(100_000)}"))
    }

    @Test
    fun rejectsSizesLargerThanATmuxWindow() {
        assertEquals(10_000, TmuxLayout.parse("abcd,10000x6,0,0,0")?.cols)
        assertNull(TmuxLayout.parse("abcd,10001x6,0,0,0"))
        assertNull(TmuxLayout.parse("abcd,40x6,0,99999999999,0"))
    }

    @Test
    fun readsTheJsonLayoutsOfTmux38() {
        // `list-windows -F '#{window_visible_layout}'` on tmux 3.8, after `split-window -h` and `split-window -v`.
        val layout = """{"V":2,"L":{"t":"h","w":80,"h":24,"x":0,"y":0,"c":[""" +
            """{"t":"p","w":40,"h":24,"x":0,"y":0,"l":1,"i":0,"I":"%0"},""" +
            """{"t":"v","w":39,"h":24,"x":41,"y":0,"c":[""" +
            """{"t":"p","w":39,"h":12,"x":41,"y":0,"l":0,"i":1,"I":"%1"},""" +
            """{"t":"p","w":39,"h":11,"x":41,"y":13,"a":true,"i":2,"I":"%2"}]}]}}"""

        assertEquals(
            TmuxWindowLayout(
                80,
                24,
                listOf(TmuxPaneRect(0, 0, 0, 40, 24), TmuxPaneRect(1, 41, 0, 39, 12), TmuxPaneRect(2, 41, 13, 39, 11)),
            ),
            TmuxLayout.parse(layout),
        )
        assertEquals(
            TmuxWindowLayout(80, 24, listOf(TmuxPaneRect(3, 0, 0, 80, 24))),
            TmuxLayout.parse("""{"V":2,"L":{"t":"p","w":80,"h":24,"x":0,"y":0,"a":true,"i":0,"I":"%3"}}"""),
        )
    }

    @Test
    fun rejectsBrokenJsonLayouts() {
        listOf(
            "{",
            "{}",
            """{"V":2}""",
            """{"V":2,"L":[]}""",
            """{"V":2,"L":{"t":"p","w":80,"h":24,"x":0,"y":0}}""",
            """{"V":2,"L":{"t":"p","w":80,"h":24,"x":0,"y":0,"I":"3"}}""",
            """{"V":2,"L":{"t":"p","w":-1,"h":24,"x":0,"y":0,"I":"%3"}}""",
            """{"V":2,"L":{"t":"p","w":20000,"h":24,"x":0,"y":0,"I":"%3"}}""",
            """{"V":2,"L":{"t":"h","w":80,"h":24,"x":0,"y":0,"c":[]}}""",
            """{"V":2,"L":{"t":"h","w":80,"h":24,"x":0,"y":0,"c":[{"t":"q"}]}}""",
            """{"V":2,"L":{"t":"x","w":80,"h":24,"x":0,"y":0,"I":"%3"}}""",
        ).forEach { assertNull(it, TmuxLayout.parse(it)) }
    }

    @Test
    fun rejectsJsonLayoutsNestedTooDeep() {
        fun nested(depth: Int): String {
            val pane = """{"t":"p","w":10,"h":10,"x":0,"y":0,"I":"%1"}"""
            val cells = (0 until depth).fold(pane) { inner, _ -> """{"t":"h","w":10,"h":10,"x":0,"y":0,"c":[$inner]}""" }
            return """{"V":2,"L":$cells}"""
        }

        assertEquals(1, TmuxLayout.parse(nested(10))?.panes?.size)
        assertNull(TmuxLayout.parse(nested(1_000)))
    }
}
