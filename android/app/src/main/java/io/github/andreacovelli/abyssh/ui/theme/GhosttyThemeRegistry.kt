package io.github.andreacovelli.abyssh.ui.theme

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** The bundled Ghostty themes. Safe to use from any thread. */
object GhosttyThemeRegistry {

    @Volatile private var themeNames: List<String> = emptyList()
    private val cache = ConcurrentHashMap<String, GhosttyTheme>()

    fun init(context: Context) {
        if (themeNames.isNotEmpty()) return
        themeNames = context.assets.list("themes")
            ?.sorted()
            ?: emptyList()
    }

    val availableThemeNames: List<String> get() = themeNames

    /** Theme [name] if it was already read, without touching the assets. */
    fun cachedTheme(name: String): GhosttyTheme? = cache[name]

    /** Theme [name], read from the assets the first time, which is too slow for the main thread. */
    fun getTheme(context: Context, name: String): GhosttyTheme? {
        if (name !in themeNames) return null
        cache[name]?.let { return it }
        val content = runCatching {
            context.assets.open("themes/$name").bufferedReader().readText()
        }.getOrNull() ?: return null
        val theme = GhosttyTheme.parse(name, content)
        cache[name] = theme
        return theme
    }
}
