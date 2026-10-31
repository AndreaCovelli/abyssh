package io.github.andreacovelli.abyssh.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PureBlackThemeTest {

    private val dark = GhosttyTheme.parse(
        "Dark",
        """
        background = #1e1e2e
        foreground = #cdd6f4
        cursor-text = #1e1e2e
        selection-foreground = #1e1e2e
        selection-background = #585b70
        """.trimIndent(),
    )

    private val light = GhosttyTheme.parse(
        "Light",
        """
        background = #eff1f5
        foreground = #4c4f69
        """.trimIndent(),
    )

    @Test
    fun `dark theme moves to a black background`() {
        val black = dark.withPureBlack()

        assertEquals(Color.Black, black.background)
        assertEquals(dark.foreground, black.foreground)
        assertEquals(dark.palette, black.palette)
        assertEquals(dark.selectionBackground, black.selectionBackground)
    }

    @Test
    fun `colors that matched the old background follow it to black`() {
        val black = dark.withPureBlack()

        assertEquals(Color.Black, black.cursorText)
        assertEquals(Color.Black, black.selectionForeground)
    }

    @Test
    fun `light theme is unchanged`() {
        assertSame(light, light.withPureBlack())
        assertEquals(light.toChuColorPalette(), light.toChuColorPalette(pureBlack = true))
    }

    @Test
    fun `pure black palette keeps panels near black and borders visible`() {
        val palette = dark.toChuColorPalette(pureBlack = true)

        assertEquals(Color.Black, palette.background)
        assertEquals(Color.Black, palette.surfaceVariant)
        assertNotEquals(Color.Black, palette.surface)
        assertTrue(palette.surface.red < dark.toChuColorPalette().surface.red)
        assertTrue(palette.border.red > palette.surface.red)
    }

    @Test
    fun `default palette is unchanged`() {
        assertEquals(dark.background, dark.toChuColorPalette().background)
    }
}
