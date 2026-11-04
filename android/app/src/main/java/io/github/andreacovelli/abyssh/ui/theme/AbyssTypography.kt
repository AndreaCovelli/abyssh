package io.github.andreacovelli.abyssh.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.andreacovelli.abyssh.R

@Immutable
data class AbyssTypeScale(
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    /** Monospace text for host addresses, keys, commands, paths, and other literal values. */
    val code: TextStyle,
    val codeSmall: TextStyle,
)

/**
 * A bundled monospace family. The styled faces are null where the family ships none
 * (Fira Code has no italic); the terminal then slants or thickens the nearest face.
 */
enum class AbyssFontOption(
    val id: String,
    val label: String,
    val regularFontResId: Int,
    val boldFontResId: Int?,
    val italicFontResId: Int?,
    val boldItalicFontResId: Int?,
) {
    JetBrainsMono(
        "jetbrains_mono",
        "JetBrains Mono",
        R.font.jetbrains_mono_regular,
        R.font.jetbrains_mono_bold,
        R.font.jetbrains_mono_italic,
        R.font.jetbrains_mono_bold_italic,
    ),
    FiraCode("fira_code", "Fira Code", R.font.fira_code_regular, R.font.fira_code_bold, null, null),
    Hack("hack", "Hack", R.font.hack_regular, R.font.hack_bold, R.font.hack_italic, R.font.hack_bold_italic),
    GeistMono(
        "geist_mono",
        "Geist Mono",
        R.font.geist_mono_regular,
        R.font.geist_mono_bold,
        R.font.geist_mono_italic,
        R.font.geist_mono_bold_italic,
    ),
    ;

    companion object {
        val default: AbyssFontOption = JetBrainsMono

        fun fromId(id: String?): AbyssFontOption {
            if (id.isNullOrBlank()) return default
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

val AbyssSymbolsFontFamily: FontFamily = FontFamily(
    Font(R.font.symbols_nerd_font_mono_regular, FontWeight.Normal),
)

/** Inter, used for the app's own text. The terminal keeps the chosen monospace font. */
val AbyssUiFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private fun monoFamily(option: AbyssFontOption): FontFamily = FontFamily(
    listOfNotNull(
        Font(option.regularFontResId, FontWeight.Normal),
        Font(option.regularFontResId, FontWeight.Medium),
        Font(option.regularFontResId, FontWeight.SemiBold),
        option.boldFontResId?.let { Font(it, FontWeight.Bold) },
        option.italicFontResId?.let { Font(it, FontWeight.Normal, FontStyle.Italic) },
        option.boldItalicFontResId?.let { Font(it, FontWeight.Bold, FontStyle.Italic) },
    ),
)

fun abyssTypographyFor(option: AbyssFontOption): AbyssTypeScale {
    val mono = monoFamily(option)
    val ui = AbyssUiFontFamily
    return AbyssTypeScale(
        headline = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        title = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        body = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        label = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = ui,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
        code = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        codeSmall = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
    )
}

val AbyssDefaultTypography: AbyssTypeScale = abyssTypographyFor(AbyssFontOption.default)

val LocalChuTypography = staticCompositionLocalOf { AbyssDefaultTypography }
val LocalChuFont = staticCompositionLocalOf { AbyssFontOption.default }

object AbyssTypography {
    val current: AbyssTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalChuTypography.current
}
