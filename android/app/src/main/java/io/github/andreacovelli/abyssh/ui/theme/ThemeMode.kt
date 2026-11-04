package io.github.andreacovelli.abyssh.ui.theme

import androidx.annotation.StringRes
import io.github.andreacovelli.abyssh.R

/**
 * Theme mode: always dark, always light, or follow the system setting.
 */
enum class ThemeMode(@StringRes val label: Int) {
    Dark(R.string.theme_dark),
    Light(R.string.theme_light),
    System(R.string.theme_system),
}
