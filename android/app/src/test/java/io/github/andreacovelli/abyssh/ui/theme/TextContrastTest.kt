package io.github.andreacovelli.abyssh.ui.theme

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextContrastTest {

    // A low-contrast theme, where half-way to the background falls far under 4.5:1.
    private val faint = GhosttyTheme.parse(
        "Faint",
        """
        background = #2e3440
        foreground = #a3abb9
        """.trimIndent(),
    )

    private val mocha = GhosttyTheme.parse(
        "Mocha",
        """
        background = #1e1e2e
        foreground = #cdd6f4
        """.trimIndent(),
    )

    // Secondary and muted text reach 4.5:1 on each panel, or match the main text where
    // even that falls short.
    private fun AbyssColorPalette.assertReadable() {
        for (panel in listOf(background, surface, surfaceVariant)) {
            val wanted = minOf(MIN_TEXT_CONTRAST, contrastRatio(textPrimary, panel))
            for (text in listOf(textSecondary, textMuted)) {
                val ratio = contrastRatio(text, panel)
                assertTrue("$name: $text on $panel is $ratio:1", ratio >= wanted)
            }
        }
    }

    @Test
    fun `muted text keeps its contrast on every panel`() {
        for (theme in listOf(faint, mocha)) {
            theme.toAbyssColorPalette().assertReadable()
            theme.toAbyssColorPalette(pureBlack = true).assertReadable()
        }
    }

    @Test
    fun `the fallback palette has readable muted text`() {
        val ratio = contrastRatio(AbyssDarkColors.textMuted, AbyssDarkColors.background)
        assertTrue("$ratio:1", ratio >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun `disabled text differs from muted text`() {
        assertNotEquals(faint.toAbyssColorPalette().textMuted, faint.toAbyssColorPalette().disabledText)
        assertNotEquals(AbyssDarkColors.textMuted, AbyssDarkColors.disabledText)
    }
}
