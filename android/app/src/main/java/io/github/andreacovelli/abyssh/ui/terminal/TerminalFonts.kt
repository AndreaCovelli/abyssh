package io.github.andreacovelli.abyssh.ui.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import io.github.andreacovelli.abyssh.ui.theme.LocalChuFont
import kotlin.math.ceil
import kotlin.math.max

internal const val PAINT_PRIMARY: Int = 0
internal const val PAINT_SYMBOLS: Int = 1
private const val PAINT_FALLBACK: Int = 2
private const val PAINT_EMOJI: Int = 3

/** Drawn by [TerminalSprites] as shapes, not by a font. */
internal const val PAINT_SPRITE: Int = 4

private const val FALLBACK_CELL_WIDTH_PX = 8f
private const val BMP_SIZE = 0x10000
private const val FALLBACK_CELL_HEIGHT_PX = 16f

/**
 * The typefaces the canvas draws with, and which of them draws each glyph. The choice
 * depends only on the typefaces, so it is cached for as long as they last.
 *
 * Order of preference:
 *  0. Box drawing, block elements, and Powerline separators are drawn by
 *     [TerminalSprites] to fill their cells, not by a font.
 *  1. A codepoint in a Nerd Font private-use range uses the primary font when its
 *     file maps it, else the symbols (Nerd Font) typeface.
 *  2. Else the primary font, when it can draw the glyph (keeps code glyph metrics).
 *  3. Else Android's system fallback, when it covers the complete glyph. It is the only
 *     typeface that shapes emoji ZWJ sequences, flag pairs, and VS16 color emoji.
 *  4. Else the bundled Noto Sans Symbols 2 as a deterministic symbol fallback.
 *  5. Finally the system typeface, to draw its missing-glyph marker.
 */
internal class TerminalFonts(
    val primary: Typeface,
    val symbols: Typeface,
    val fallback: Typeface,
    // Icon codepoints the primary font file itself maps (e.g. its own Powerline
    // arrows). Read from the file because Paint.hasGlyph() also counts system fonts.
    private val primaryPuaCoverage: IntArray,
) {
    // hasGlyph depends only on the typeface, so these probes never change size.
    private val primaryProbe = Paint().apply { typeface = primary }
    private val fallbackProbe = Paint().apply { typeface = fallback }
    private val emojiProbe = Paint().apply { typeface = Typeface.DEFAULT }
    private val singleGlyphs = HashMap<Int, String>(256).apply {
        for (cp in 33..126) this[cp] = cp.toChar().toString()
    }
    private val singleChoices = HashMap<Int, Int>(256)

    // Choices for the Basic Multilingual Plane, plus one so 0 means not looked up yet.
    // The renderer asks for every cell it draws, and an array avoids boxing the key.
    private val bmpChoices = ByteArray(BMP_SIZE)
    private val clusterChoices = HashMap<String, Int>(64)

    fun glyphFor(codepoint: Int): String = singleGlyphs.getOrPut(codepoint) { String(Character.toChars(codepoint)) }

    fun choiceFor(codepoint: Int): Int {
        // Fast path for the overwhelmingly common command-output ASCII glyphs.
        if (codepoint in 0x21..0x7E) return PAINT_PRIMARY
        if (isSpriteCodepoint(codepoint)) return PAINT_SPRITE
        if (codepoint in 0 until BMP_SIZE) {
            val cached = bmpChoices[codepoint].toInt()
            if (cached != 0) return cached - 1
            return lookUpChoice(codepoint).also { bmpChoices[codepoint] = (it + 1).toByte() }
        }
        return singleChoices.getOrPut(codepoint) { lookUpChoice(codepoint) }
    }

    private fun lookUpChoice(codepoint: Int): Int = if (isNerdFontPrivateUse(codepoint)) {
        privateUseChoice(codepoint, primaryPuaCoverage)
    } else {
        choiceForGlyph(glyphFor(codepoint))
    }

    fun choiceFor(cluster: String): Int = clusterChoices.getOrPut(cluster) {
        val first = cluster.codePointAt(0)
        if (isNerdFontPrivateUse(first)) privateUseChoice(first, primaryPuaCoverage) else choiceForGlyph(cluster)
    }

    /** The paint choice for one cell of [row]. */
    fun choiceAt(row: TerminalRow, col: Int): Int = if (row.hasGraphemeExtras(col)) {
        choiceFor(row.glyphAt(col))
    } else {
        choiceFor(row.codepoints[col])
    }

    private fun choiceForGlyph(glyph: String): Int = when {
        primaryProbe.hasGlyph(glyph) -> PAINT_PRIMARY
        emojiProbe.hasGlyph(glyph) -> PAINT_EMOJI
        fallbackProbe.hasGlyph(glyph) -> PAINT_FALLBACK
        else -> PAINT_EMOJI
    }
}

/**
 * Icon codepoints use the primary font only when its file really maps them
 * (its own Powerline arrows match the cell metrics best). Everything else goes
 * to the bundled Nerd Font, never to a system font that may map the codepoint
 * to an unrelated glyph.
 */
internal fun privateUseChoice(codepoint: Int, primaryPuaCoverage: IntArray): Int =
    if (primaryPuaCoverage.binarySearch(codepoint) >= 0) PAINT_PRIMARY else PAINT_SYMBOLS

@Composable
internal fun rememberTerminalFonts(): TerminalFonts {
    val context = LocalContext.current
    val fontOption = LocalChuFont.current
    return remember(context, fontOption) {
        TerminalFonts(
            primary = context.loadFont(fontOption.regularFontResId, Typeface.MONOSPACE),
            symbols = context.loadFont(R.font.symbols_nerd_font_mono_regular, Typeface.MONOSPACE),
            fallback = context.loadFont(R.font.noto_sans_symbols2_regular, Typeface.DEFAULT),
            primaryPuaCoverage = FontCmap.privateUseCoverage(context, fontOption.regularFontResId),
        )
    }
}

private fun Context.loadFont(fontResId: Int, default: Typeface): Typeface =
    runCatching { ResourcesCompat.getFont(this, fontResId) }.getOrNull() ?: default

/** One text paint per typeface, at the canvas's font size. */
internal class TerminalTextPaints(fonts: TerminalFonts, sizePx: Float) {
    private val primary = textPaint(fonts.primary, sizePx)
    private val symbols = textPaint(fonts.symbols, sizePx)
    private val fallback = textPaint(fonts.fallback, sizePx)

    // The system typeface reaches Android's NotoColorEmoji fallback; the symbols Nerd
    // Font has no color emoji or ZWJ shaping.
    private val emoji = textPaint(Typeface.DEFAULT, sizePx)

    fun forChoice(choice: Int): Paint = when (choice) {
        PAINT_SYMBOLS -> symbols
        PAINT_FALLBACK -> fallback
        PAINT_EMOJI -> emoji
        else -> primary
    }

    /** The cell size, measured from the primary font. */
    fun cellMetrics(): TerminalCellMetrics {
        val metrics = primary.fontMetrics
        val height = (metrics.descent - metrics.ascent).takeIf { it > 1f } ?: FALLBACK_CELL_HEIGHT_PX
        val width = primary.measureText("M").takeIf { it > 1f } ?: FALLBACK_CELL_WIDTH_PX
        return TerminalCellMetrics(
            widthPx = max(1, ceil(width).toInt()),
            heightPx = max(1, ceil(height).toInt()),
            baseline = -metrics.ascent,
        )
    }

    private fun textPaint(face: Typeface, sizePx: Float) = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        textSize = sizePx
        typeface = face
    }
}

/**
 * The size of one cell. It is whole pixels so the renderer agrees with Ghostty's grid;
 * sub-pixel drift would build up across rows and columns and misalign Kitty images.
 */
internal data class TerminalCellMetrics(val widthPx: Int, val heightPx: Int, val baseline: Float) {
    val width: Float get() = widthPx.toFloat()
    val height: Float get() = heightPx.toFloat()
}
