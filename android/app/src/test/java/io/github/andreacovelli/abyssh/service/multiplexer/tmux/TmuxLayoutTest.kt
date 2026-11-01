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
}
