package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.terminal.AccessoryBarPosition
import io.github.andreacovelli.abyssh.ui.terminal.AccessoryBarPositions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the terminal's accessory bar sits, chosen separately for portrait and landscape. */
class AccessoryBarSettings internal constructor(private val prefs: SharedPreferences) {

    private val _positions = MutableStateFlow(
        AccessoryBarPositions(
            portrait = AccessoryBarPosition.fromName(prefs.getString(KEY_PORTRAIT_POSITION, null)),
            landscape = AccessoryBarPosition.fromName(prefs.getString(KEY_LANDSCAPE_POSITION, null)),
        ),
    )
    val positions: StateFlow<AccessoryBarPositions> = _positions.asStateFlow()

    fun setPortraitPosition(position: AccessoryBarPosition) {
        prefs.edit { putString(KEY_PORTRAIT_POSITION, position.name) }
        _positions.update { it.copy(portrait = position) }
    }

    fun setLandscapePosition(position: AccessoryBarPosition) {
        prefs.edit { putString(KEY_LANDSCAPE_POSITION, position.name) }
        _positions.update { it.copy(landscape = position) }
    }

    private companion object {
        const val KEY_PORTRAIT_POSITION = "terminal_accessory_bar_position_portrait"
        const val KEY_LANDSCAPE_POSITION = "terminal_accessory_bar_position_landscape"
    }
}
