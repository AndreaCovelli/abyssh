package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxLayoutTest {
    @Test
    fun readsASinglePane() {
        assertEquals(mapOf(0 to TmuxPaneSize(40, 6)), TmuxLayout.paneSizes("f25d,40x6,0,0,0"))
    }

    @Test
    fun readsNestedSplits() {
        assertEquals(
            mapOf(1 to TmuxPaneSize(50, 30), 2 to TmuxPaneSize(49, 14), 3 to TmuxPaneSize(49, 15)),
            TmuxLayout.paneSizes("eb8f,100x30,0,0{50x30,0,0,1,49x30,51,0[49x14,51,0,2,49x15,51,15,3]}"),
        )
    }

    @Test
    fun rejectsBrokenLayouts() {
        assertEquals(emptyMap<Int, TmuxPaneSize>(), TmuxLayout.paneSizes("eb8f,100x30,0,0{50x30,0,0,1"))
        assertEquals(emptyMap<Int, TmuxPaneSize>(), TmuxLayout.paneSizes("nonsense"))
    }
}
