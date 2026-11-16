package io.github.andreacovelli.abyssh.ui.terminal

/** The edge of the terminal screen the accessory bar sits on. */
enum class AccessoryBarPosition {
    Bottom,
    Top,
    Left,
    Right,
    ;

    /** Whether the keys run down a side of the terminal, as a column, rather than along it. */
    val isVertical: Boolean get() = this == Left || this == Right

    companion object {
        /** The position stored as [name], or the bottom for a name that is missing or unknown. */
        fun fromName(name: String?): AccessoryBarPosition = entries.firstOrNull { it.name == name } ?: Bottom
    }
}

/** Where the accessory bar sits in each orientation of the screen. */
data class AccessoryBarPositions(
    val portrait: AccessoryBarPosition = AccessoryBarPosition.Bottom,
    val landscape: AccessoryBarPosition = AccessoryBarPosition.Bottom,
) {
    fun forOrientation(isLandscape: Boolean): AccessoryBarPosition = if (isLandscape) landscape else portrait
}
