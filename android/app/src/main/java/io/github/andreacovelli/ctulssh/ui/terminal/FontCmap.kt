package io.github.andreacovelli.ctulssh.ui.terminal

import android.content.Context
import java.nio.ByteBuffer

/**
 * Reads which codepoints a font file maps itself, straight from its `cmap` table.
 *
 * Android's Paint.hasGlyph() also searches the system fallback fonts, so it can't
 * tell whether the font file itself has a glyph. Some OEM CJK system fonts map
 * Private Use Area codepoints to ideographs. When the canvas trusted hasGlyph(),
 * Nerd Font icons (e.g. the eza/lsd folder icon U+E5FF) showed up as
 * Chinese/Japanese characters instead of reaching the Nerd Font.
 */
internal object FontCmap {
    private val puaCoverageCache = HashMap<Int, IntArray>()

    /** Sorted Nerd Font private-use codepoints that the font resource maps itself. */
    fun privateUseCoverage(context: Context, fontResId: Int): IntArray =
        synchronized(puaCoverageCache) {
            puaCoverageCache.getOrPut(fontResId) {
                runCatching {
                    val bytes = context.resources.openRawResource(fontResId).use { it.readBytes() }
                    codepoints(bytes, ::isNerdFontPrivateUse)
                }.getOrDefault(IntArray(0))
            }
        }

    /**
     * Returns the sorted codepoints accepted by [include] that the font maps to a
     * real glyph. Supports the two cmap formats used by modern fonts: format 12
     * (full Unicode) and format 4 (BMP). Returns empty for unsupported fonts.
     */
    fun codepoints(font: ByteArray, include: (Int) -> Boolean): IntArray {
        val buf = ByteBuffer.wrap(font)
        val cmap = findTable(buf, "cmap") ?: return IntArray(0)
        val subtable = pickSubtable(buf, cmap) ?: return IntArray(0)
        val out = ArrayList<Int>()
        when (buf.u16(subtable)) {
            12 -> readFormat12(buf, subtable, include, out)
            4 -> readFormat4(buf, subtable, include, out)
            else -> return IntArray(0)
        }
        return out.distinct().sorted().toIntArray()
    }

    private fun findTable(buf: ByteBuffer, tag: String): Int? {
        val numTables = buf.u16(4)
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            val recordTag = String(ByteArray(4) { buf.get(record + it) }, Charsets.US_ASCII)
            if (recordTag == tag) return buf.u32(record + 8).toInt()
        }
        return null
    }

    /** Prefers a full-Unicode format 12 subtable, then any Unicode format 4 one. */
    private fun pickSubtable(buf: ByteBuffer, cmap: Int): Int? {
        var format4: Int? = null
        for (i in 0 until buf.u16(cmap + 2)) {
            val record = cmap + 4 + i * 8
            val platform = buf.u16(record)
            val encoding = buf.u16(record + 2)
            val isUnicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
            if (!isUnicode) continue
            val offset = cmap + buf.u32(record + 4).toInt()
            when (buf.u16(offset)) {
                12 -> return offset
                4 -> if (format4 == null) format4 = offset
            }
        }
        return format4
    }

    private fun readFormat12(buf: ByteBuffer, table: Int, include: (Int) -> Boolean, out: MutableList<Int>) {
        val numGroups = buf.u32(table + 12)
        for (g in 0 until numGroups) {
            val group = table + 16 + (g * 12).toInt()
            val start = buf.u32(group).toInt()
            val end = buf.u32(group + 4).toInt()
            val startGlyph = buf.u32(group + 8)
            for (cp in start..end) {
                if (startGlyph + (cp - start) != 0L && include(cp)) out.add(cp)
            }
        }
    }

    private fun readFormat4(buf: ByteBuffer, table: Int, include: (Int) -> Boolean, out: MutableList<Int>) {
        val segCount = buf.u16(table + 6) / 2
        val endCodes = table + 14
        val startCodes = endCodes + segCount * 2 + 2
        val idDeltas = startCodes + segCount * 2
        val idRangeOffsets = idDeltas + segCount * 2
        for (s in 0 until segCount) {
            val start = buf.u16(startCodes + s * 2)
            val end = buf.u16(endCodes + s * 2)
            val delta = buf.u16(idDeltas + s * 2)
            val rangeOffsetAddr = idRangeOffsets + s * 2
            val rangeOffset = buf.u16(rangeOffsetAddr)
            for (cp in start..end) {
                if (cp == 0xFFFF || !include(cp)) continue
                val glyph = if (rangeOffset == 0) {
                    (cp + delta) and 0xFFFF
                } else {
                    val raw = buf.u16(rangeOffsetAddr + rangeOffset + (cp - start) * 2)
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) out.add(cp)
            }
        }
    }

    private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xFFFF
    private fun ByteBuffer.u32(at: Int): Long = getInt(at).toLong() and 0xFFFFFFFFL
}

/**
 * Private Use Area ranges where Nerd Font glyphs live (devicons, powerline,
 * weather icons, file-type icons, etc.).
 */
internal fun isNerdFontPrivateUse(cp: Int): Boolean {
    return (cp in 0xE000..0xF8FF) ||
        (cp in 0xF0000..0xFFFFD) ||
        (cp in 0x100000..0x10FFFD)
}
