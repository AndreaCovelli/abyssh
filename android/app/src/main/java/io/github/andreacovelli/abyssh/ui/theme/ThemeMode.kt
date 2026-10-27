package io.github.andreacovelli.abyssh.ui.theme

/**
 * Theme mode: always dark, always light, or follow the system setting.
 */
enum class ThemeMode(val label: String) {
    Dark("dark"),
    Light("light"),
    System("system"),
}
