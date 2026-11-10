package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import java.util.IdentityHashMap

private const val ASCII_FIRST: Int = 0x21
private const val ASCII_LAST: Int = 0x7E
private const val NO_GLYPH: Int = -1

// Shaped runs kept per paint; enough for the words of several screens of output.
private const val SHAPED_RUNS_PER_PAINT: Int = 1024

/**
 * Draws terminal text from glyph IDs with [Canvas.drawGlyphs], which skips the text
 * layout that [Canvas.drawText] runs for every string it draws. Each paint's printable
 * ASCII glyphs are looked up once and placed one per cell; other runs are shaped once
 * with [TextRunShaper] and reused while they stay in a cache.
 *
 * The paint's typeface is ignored when drawing glyphs, but its size, fake bold, skew,
 * color, and alpha still apply, so styled text looks as [Canvas.drawText] draws it.
 */
internal class TerminalGlyphs {
    private val asciiTables = IdentityHashMap<Paint, AsciiGlyphs?>()
    private val shapedRuns = IdentityHashMap<Paint, ShapedRunCache>()
    private var glyphIds = IntArray(256)
    private var positions = FloatArray(512)

    /** Drops every cached glyph, for paints that won't be drawn with again. */
    fun clear() {
        asciiTables.clear()
        shapedRuns.clear()
    }

    /**
     * Draws [codepoints] from [start] to [end], printable ASCII, one glyph per cell from
     * [x]. Returns false, drawing nothing, when the paint's font lacks one of the glyphs.
     */
    fun drawAscii(
        canvas: Canvas,
        codepoints: IntArray,
        start: Int,
        end: Int,
        x: Float,
        baseline: Float,
        cellWidth: Float,
        paint: Paint,
    ): Boolean {
        val table = asciiTables.getOrPut(paint) { AsciiGlyphs.of(paint) } ?: return false
        ensureCapacity(end - start)
        var count = 0
        for (col in start until end) {
            val codepoint = codepoints[col]
            if (codepoint == ' '.code) continue
            val id = table.glyphId(codepoint)
            if (id == NO_GLYPH) return false
            glyphIds[count] = id
            positions[count * 2] = x + (col - start) * cellWidth
            positions[count * 2 + 1] = baseline
            count++
        }
        if (count > 0) canvas.drawGlyphs(glyphIds, 0, positions, 0, count, table.font, paint)
        return true
    }

    /** The glyphs of [text] shaped with [paint], from the cache when it was shaped before. */
    fun shape(text: CharSequence, from: Int, to: Int, paint: Paint): ShapedRun {
        val cache = shapedRuns.getOrPut(paint) { ShapedRunCache() }
        val key = text.subSequence(from, to).toString()
        return cache[key] ?: ShapedRun.of(key, paint).also { cache[key] = it }
    }

    /** Draws [run] with its origin at [x] on [baseline]. */
    fun draw(canvas: Canvas, run: ShapedRun, x: Float, baseline: Float, paint: Paint) {
        ensureCapacity(run.glyphIds.size)
        var first = 0
        for (segment in run.fonts.indices) {
            val last = run.fontEnds[segment]
            val count = last - first
            for (i in 0 until count) {
                glyphIds[i] = run.glyphIds[first + i]
                positions[i * 2] = x + run.positions[(first + i) * 2]
                positions[i * 2 + 1] = baseline + run.positions[(first + i) * 2 + 1]
            }
            if (count > 0) canvas.drawGlyphs(glyphIds, 0, positions, 0, count, run.fonts[segment], paint)
            first = last
        }
    }

    private fun ensureCapacity(glyphs: Int) {
        if (glyphIds.size < glyphs) glyphIds = IntArray(glyphs)
        if (positions.size < glyphs * 2) positions = FloatArray(glyphs * 2)
    }
}

/**
 * Glyph IDs for printable ASCII in one paint's font. A character whose glyph comes
 * from another font, or that shapes to several glyphs, has none, and runs holding it
 * are drawn as text instead.
 */
private class AsciiGlyphs(val font: Font, private val ids: IntArray) {
    fun glyphId(codepoint: Int): Int =
        if (codepoint in ASCII_FIRST..ASCII_LAST) ids[codepoint - ASCII_FIRST] else NO_GLYPH

    companion object {
        /** The table for [paint], or null when it has no font of its own for "M". */
        fun of(paint: Paint): AsciiGlyphs? {
            val font = shapeOne("M", paint)?.getFont(0) ?: return null
            val ids = IntArray(ASCII_LAST - ASCII_FIRST + 1) { i ->
                val glyphs = shapeOne((ASCII_FIRST + i).toChar().toString(), paint)
                if (glyphs != null && glyphs.getFont(0) == font) glyphs.getGlyphId(0) else NO_GLYPH
            }
            return AsciiGlyphs(font, ids)
        }

        private fun shapeOne(text: String, paint: Paint): PositionedGlyphs? =
            TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
                .takeIf { it.glyphCount() == 1 }
    }
}

/**
 * One shaped string: its glyphs, their positions relative to the run's origin as x and
 * y pairs, and the fonts they come from. Glyphs up to `fontEnds[i]` use `fonts[i]`.
 */
internal class ShapedRun(
    val glyphIds: IntArray,
    val positions: FloatArray,
    val fonts: Array<Font>,
    val fontEnds: IntArray,
    val advance: Float,
) {
    companion object {
        fun of(text: String, paint: Paint): ShapedRun {
            val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
            val count = glyphs.glyphCount()
            val fonts = ArrayList<Font>()
            val fontEnds = ArrayList<Int>()
            for (i in 0 until count) {
                val font = glyphs.getFont(i)
                // Fallback fonts take over a few glyphs at a time, so compare with the last.
                if (fonts.isEmpty() || fonts.last() != font) {
                    if (fonts.isNotEmpty()) fontEnds += i
                    fonts += font
                }
            }
            if (fonts.isNotEmpty()) fontEnds += count
            return ShapedRun(
                glyphIds = IntArray(count) { glyphs.getGlyphId(it) },
                positions = FloatArray(count * 2) { if (it % 2 == 0) glyphs.getGlyphX(it / 2) else glyphs.getGlyphY(it / 2) },
                fonts = fonts.toTypedArray(),
                fontEnds = fontEnds.toIntArray(),
                advance = glyphs.advance,
            )
        }
    }
}

/** The least recently drawn runs go first. */
private class ShapedRunCache : LinkedHashMap<String, ShapedRun>(SHAPED_RUNS_PER_PAINT, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShapedRun>?): Boolean =
        size > SHAPED_RUNS_PER_PAINT
}
