package io.github.andreacovelli.abyssh.ui.screens.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenableLinkTest {

    @Test
    fun `only web and mail links open`() {
        assertTrue(isOpenableLink("https://example.org/a"))
        assertTrue(isOpenableLink("HTTP://example.org"))
        assertTrue(isOpenableLink("mailto:me@example.org"))
        assertFalse(isOpenableLink("file:///sdcard/secret"))
        assertFalse(isOpenableLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isOpenableLink("market://details?id=x"))
        assertFalse(isOpenableLink("example.org"))
    }
}
