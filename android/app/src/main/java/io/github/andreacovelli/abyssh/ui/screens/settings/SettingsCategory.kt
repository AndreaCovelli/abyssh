package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.annotation.StringRes
import io.github.andreacovelli.abyssh.R

enum class SettingsCategory(@StringRes val label: Int) {
    General(R.string.settings_category_general),
    Terminal(R.string.settings_category_terminal),
    Keys(R.string.settings_category_keys),
}
