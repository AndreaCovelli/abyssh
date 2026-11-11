package io.github.andreacovelli.abyssh.model

/** When the terminal draws typed text before the host echoes it. */
enum class EchoPredictionMode {
    Off,

    /** Only once the echo has been measured to take long enough to notice. */
    Auto,
    Always,
    ;

    companion object {
        val Default = Auto

        fun fromName(name: String?): EchoPredictionMode = entries.firstOrNull { it.name == name } ?: Default
    }
}
