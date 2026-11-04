package io.github.andreacovelli.abyssh.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GhosttyTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursorColor: Color,
    val cursorText: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val palette: List<Color>,
) {
    companion object {
        fun parse(name: String, content: String): GhosttyTheme {
            val props = mutableMapOf<String, String>()
            val paletteColors = arrayOfNulls<Color>(16)

            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val eqIndex = line.indexOf('=')
                    if (eqIndex < 0) return@forEach
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()

                    if (key == "palette") {
                        val sepIndex = value.indexOf('=')
                        if (sepIndex >= 0) {
                            val idx = value.substring(0, sepIndex).toIntOrNull() ?: return@forEach
                            if (idx in 0..15) {
                                paletteColors[idx] = parseHexColor(value.substring(sepIndex + 1))
                            }
                        }
                    } else {
                        props[key] = value
                    }
            }

            val bg = parseHexColor(props["background"] ?: "#000000")
            val fg = parseHexColor(props["foreground"] ?: "#ffffff")

            return GhosttyTheme(
                name = name,
                background = bg,
                foreground = fg,
                cursorColor = parseHexColor(props["cursor-color"] ?: props["foreground"] ?: "#ffffff"),
                cursorText = parseHexColor(props["cursor-text"] ?: props["background"] ?: "#000000"),
                selectionBackground = parseHexColor(props["selection-background"] ?: props["foreground"] ?: "#ffffff"),
                selectionForeground = parseHexColor(props["selection-foreground"] ?: props["background"] ?: "#000000"),
                palette = List(16) { paletteColors[it] ?: defaultTerminalPaletteColor(it) },
            )
        }

        private fun parseHexColor(hex: String): Color {
            val h = hex.trimStart('#')
            val argb = when (h.length) {
                6 -> h.toLongOrNull(16)?.let { 0xFF000000 or it }
                8 -> h.toLongOrNull(16)
                else -> null
            }
            return Color((argb ?: 0xFF000000).toInt())
        }
    }
}

private fun Color.mix(other: Color, fraction: Float): Color {
    val inv = 1f - fraction
    return Color(
        red = this.red * inv + other.red * fraction,
        green = this.green * inv + other.green * fraction,
        blue = this.blue * inv + other.blue * fraction,
        alpha = this.alpha * inv + other.alpha * fraction,
    )
}

/** The contrast WCAG asks of body text. */
internal const val MIN_TEXT_CONTRAST = 4.5f

private const val DISABLED_TEXT_MIX = 0.6f
private const val READABLE_MIX_STEP = 0.02f

/** The WCAG contrast ratio of two colors, from 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * This color mixed toward [other] by [fraction], or by less when that would bring its
 * contrast with any of [backdrops] under [MIN_TEXT_CONTRAST].
 */
private fun Color.readableMix(other: Color, fraction: Float, backdrops: List<Color>): Color {
    var amount = fraction
    while (amount > 0f) {
        val mixed = mix(other, amount)
        if (backdrops.all { contrastRatio(mixed, it) >= MIN_TEXT_CONTRAST }) return mixed
        amount -= READABLE_MIX_STEP
    }
    return this
}

/** Whether the theme has a dark background, the only kind [withPureBlack] changes. */
val GhosttyTheme.isDark: Boolean get() = background.perceivedLuminance() < 0.5f

/**
 * The theme on a pure black background, for OLED screens. Light themes come back
 * unchanged. Cursor text and selection text that matched the old background
 * follow it to black, so they keep their contrast.
 */
fun GhosttyTheme.withPureBlack(): GhosttyTheme {
    if (!isDark) return this
    val oldBackground = background
    fun Color.followBackground() = if (this == oldBackground) PureBlack else this
    return copy(
        background = PureBlack,
        cursorText = cursorText.followBackground(),
        selectionForeground = selectionForeground.followBackground(),
    )
}

/**
 * The UI palette for this theme. With [pureBlack], a dark theme gets a black
 * background and panels close to black, which borders keep apart.
 */
fun GhosttyTheme.toChuColorPalette(pureBlack: Boolean = false): AbyssColorPalette {
    if (pureBlack && isDark) return withPureBlack().toUiPalette(oled = true)
    return toUiPalette(oled = false)
}

private fun GhosttyTheme.toUiPalette(oled: Boolean): AbyssColorPalette {
    val white = Color(0xFFFFFFFF)
    val contrast = if (isDark) white else PureBlack

    // Panels: lighter touch on light themes so pale sage/cream backgrounds
    // don't get muddied; keep the existing weight on dark themes. On pure
    // black, panels stay near black so the screen stays mostly off.
    val surface = background.mix(
        contrast,
        when {
            oled -> 0.08f
            isDark -> 0.15f
            else -> 0.06f
        },
    )
    val surfaceVariant = if (oled) background else background.mix(contrast, if (isDark) 0.04f else 0.02f)
    // Borders need more punch on light themes — the eye is less forgiving
    // of low-contrast outlines on bright backgrounds.
    val border = background.mix(contrast, if (isDark) 0.22f else 0.28f)

    // Secondary and muted text fade toward the background only as far as they stay
    // readable on every panel color.
    val panels = listOf(background, surface, surfaceVariant)
    val textPrimary = foreground
    val textSecondary = foreground.readableMix(background, 0.28f, panels)
    val textMuted = foreground.readableMix(background, 0.5f, panels)

    // Brand accent is derived from the theme's own foreground rather than
    // ANSI palette[4]. Borrowing ANSI blue makes every UI button clash with
    // non-blue themes (sage, sepia, rose…); deriving from foreground gives
    // the classic terminal "inverted text" button that always harmonises.
    val accent = foreground.mix(background, if (isDark) 0.15f else 0.08f)
    val accentSecondary = palette[4].mix(background, if (isDark) 0.15f else 0.35f)
    val onAccent = background

    val disabledSurface = surface.mix(background, 0.5f)
    // Disabled controls are exempt from the contrast minimum, and dimmer than muted
    // text so they read as unavailable.
    val disabledText = foreground.mix(background, DISABLED_TEXT_MIX)

    return AbyssColorPalette(
        name = this.name,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        border = border,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        accent = accent,
        accentSecondary = accentSecondary,
        error = palette[1],
        success = palette[2],
        warning = palette[3],
        onAccent = onAccent,
        disabledSurface = disabledSurface,
        disabledText = disabledText,
    )
}

fun GhosttyTheme.toTerminalPaletteBytes(): ByteArray {
    val colors = MutableList(256, ::defaultTerminalPaletteColor)
    for (index in palette.indices) {
        colors[index] = palette[index]
    }

    return ByteArray(colors.size * 3).also { bytes ->
        colors.forEachIndexed { index, color ->
            val offset = index * 3
            bytes[offset] = colorChannelToByte(color.red)
            bytes[offset + 1] = colorChannelToByte(color.green)
            bytes[offset + 2] = colorChannelToByte(color.blue)
        }
    }
}

fun Color.toRgbIntArray(): IntArray = intArrayOf(
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
)

private fun colorChannelToByte(value: Float): Byte =
    (value * 255f).roundToInt().coerceIn(0, 255).toByte()

private fun defaultTerminalPaletteColor(index: Int): Color {
    if (index in DEFAULT_ANSI_COLORS.indices) {
        return DEFAULT_ANSI_COLORS[index]
    }

    return when (index) {
        in 16..231 -> {
            val cubeIndex = index - 16
            val red = XTERM_CUBE_LEVELS[cubeIndex / 36]
            val green = XTERM_CUBE_LEVELS[(cubeIndex / 6) % 6]
            val blue = XTERM_CUBE_LEVELS[cubeIndex % 6]
            Color(red = red / 255f, green = green / 255f, blue = blue / 255f)
        }

        in 232..255 -> {
            val level = 8 + (index - 232) * 10
            Color(red = level / 255f, green = level / 255f, blue = level / 255f)
        }

        else -> Color(0xFF000000)
    }
}

private val DEFAULT_ANSI_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF800000),
    Color(0xFF008000),
    Color(0xFF808000),
    Color(0xFF000080),
    Color(0xFF800080),
    Color(0xFF008080),
    Color(0xFFC0C0C0),
    Color(0xFF808080),
    Color(0xFFFF0000),
    Color(0xFF00FF00),
    Color(0xFFFFFF00),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFF00FFFF),
    Color(0xFFFFFFFF),
)

private val PureBlack = Color(0xFF000000)

private val XTERM_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)
