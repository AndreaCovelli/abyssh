package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.service.terminal.TerminalSnapshot

// ASCII URL characters only, so box-drawing borders and quotes around a URL stay out.
private val URL_REGEX = Regex("""(?:(?:https?|ftp)://|www\.)[A-Za-z0-9\-._~:/?#\[\]@!$&()*+,;=%]+""")
private const val TRAILING_PUNCTUATION = ".,;:!?"

/**
 * The URL written on screen at [cellIndex], or null. A URL that reaches the end of a
 * row continues on the next one, as long lines wrap.
 */
fun TerminalSnapshot.urlAt(cellIndex: Int): String? {
    if (cellIndex !in codepoints.indices) return null
    val text = visibleText()
    for (match in URL_REGEX.findAll(text)) {
        if (match.range.first > cellIndex) break
        val url = trimUrl(match.value)
        if (cellIndex < match.range.first + url.length) {
            return if (url.startsWith("www.")) "https://$url" else url
        }
    }
    return null
}

/** One character per cell. Cells that aren't plain characters become spaces. */
private fun TerminalSnapshot.visibleText(): String = buildString(codepoints.size) {
    for (i in codepoints.indices) {
        val cp = codepoints[i]
        val plain = cp in 0x21..0xFFFF && !Character.isSurrogate(cp.toChar()) && !isSpacerContinuation(i)
        append(if (plain) cp.toChar() else ' ')
    }
}

/** Drops sentence punctuation after a URL and closing brackets that weren't opened in it. */
internal fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        url = when {
            last in TRAILING_PUNCTUATION -> url.dropLast(1)
            last == ')' && url.count { it == '(' } < url.count { it == ')' } -> url.dropLast(1)
            last == ']' && url.count { it == '[' } < url.count { it == ']' } -> url.dropLast(1)
            else -> return url
        }
    }
    return url
}
