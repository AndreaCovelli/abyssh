package io.github.andreacovelli.abyssh.data.secure

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers that saved hosts and keys were deleted because the data key was lost,
 * until the user has been told.
 */
class DataResetNotice private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pending = MutableStateFlow(prefs.getBoolean(KEY_PENDING, false))
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun record() {
        prefs.edit { putBoolean(KEY_PENDING, true) }
        _pending.value = true
    }

    fun dismiss() {
        prefs.edit { remove(KEY_PENDING) }
        _pending.value = false
    }

    companion object {
        private const val PREFS_NAME = "data_reset"
        private const val KEY_PENDING = "pending"

        @Volatile private var instance: DataResetNotice? = null

        fun getInstance(context: Context): DataResetNotice = instance ?: synchronized(this) {
            instance ?: DataResetNotice(context.applicationContext).also { instance = it }
        }
    }
}
