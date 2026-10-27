package io.github.andreacovelli.ctulssh.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.andreacovelli.ctulssh.R

@Immutable
data class CtulTypeScale(
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
)

enum class CtulFontOption(
    val id: String,
    val label: String,
    val regularFontResId: Int,
) {
    JetBrainsMono("jetbrains_mono", "JetBrains Mono", R.font.jetbrains_mono_regular),
    FiraCode("fira_code", "Fira Code", R.font.fira_code_regular),
    Hack("hack", "Hack", R.font.hack_regular),
    GeistMono("geist_mono", "Geist Mono", R.font.geist_mono_regular),
    ;

    companion object {
        val default: CtulFontOption = JetBrainsMono

        fun fromId(id: String?): CtulFontOption {
            if (id.isNullOrBlank()) return default
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

val CtulSymbolsFontFamily: FontFamily = FontFamily(
    Font(R.font.symbols_nerd_font_mono_regular, FontWeight.Normal),
)

private fun monoFamily(option: CtulFontOption): FontFamily = FontFamily(
    Font(option.regularFontResId, FontWeight.Normal),
    Font(option.regularFontResId, FontWeight.Medium),
    Font(option.regularFontResId, FontWeight.SemiBold),
)

fun ctulTypographyFor(option: CtulFontOption): CtulTypeScale {
    val mono = monoFamily(option)
    return CtulTypeScale(
        headline = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        title = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        body = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        label = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

val CtulDefaultTypography: CtulTypeScale = ctulTypographyFor(CtulFontOption.default)

val LocalChuTypography = staticCompositionLocalOf { CtulDefaultTypography }
val LocalChuFont = staticCompositionLocalOf { CtulFontOption.default }

object CtulTypography {
    val current: CtulTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalChuTypography.current
}
