package com.jossephus.chuchu.ui.terminal

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCmapTest {

    private fun font(name: String): ByteArray = File("src/main/res/font/$name.ttf").readBytes()

    private fun pua(name: String): IntArray = FontCmap.codepoints(font(name), ::isNerdFontPrivateUse)

    private val powerline = intArrayOf(0xE0A0, 0xE0A1, 0xE0A2, 0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3)

    @Test
    fun `primary fonts only map their own Powerline glyphs in the icon range`() {
        assertArrayEquals(powerline, pua("jetbrains_mono_regular"))
        assertArrayEquals(powerline, pua("hack_regular"))
        assertArrayEquals(intArrayOf(0xE000, 0xE001, 0xE002, 0xE003) + powerline, pua("fira_code_regular"))
        assertArrayEquals(intArrayOf(0xF8FF), pua("geist_mono_regular"))
    }

    @Test
    fun `nerd font symbols map folder icons in BMP and supplementary planes`() {
        val symbols = pua("symbols_nerd_font_mono_regular").toSet()
        // eza folder, Font Awesome folder/folder-open, Material Design folder.
        listOf(0xE5FF, 0xF07B, 0xF115, 0xF024B).forEach { assertTrue("missing %x".format(it), it in symbols) }
    }

    @Test
    fun `reads regular text codepoints too`() {
        val ascii = FontCmap.codepoints(font("jetbrains_mono_regular")) { it in 0x41..0x5A }
        assertEquals(26, ascii.size)
    }

    @Test
    fun `icon codepoints missing from the primary font go to the nerd font`() {
        val coverage = pua("jetbrains_mono_regular")
        assertEquals(PAINT_PRIMARY, privateUseChoice(0xE0B0, coverage))
        assertEquals(PAINT_SYMBOLS, privateUseChoice(0xE5FF, coverage))
        assertEquals(PAINT_SYMBOLS, privateUseChoice(0xF024B, coverage))
    }

    @Test
    fun `garbage input yields no coverage`() {
        assertEquals(0, FontCmap.codepoints(ByteArray(64)) { true }.size)
    }
}
