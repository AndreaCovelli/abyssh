package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The order hosts are listed in, within each section of the server list. */
enum class HostSort {
    Name,
    LastConnected,
}

/** How the server list arranges the hosts. */
class HostListSettings internal constructor(private val prefs: SharedPreferences) {

    private val _sort = MutableStateFlow(
        HostSort.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: HostSort.Name,
    )
    val sort: StateFlow<HostSort> = _sort.asStateFlow()

    fun setSort(sort: HostSort) {
        prefs.edit { putString(KEY_SORT, sort.name) }
        _sort.value = sort
    }

    private val _collapsedSections = MutableStateFlow(prefs.getStringSet(KEY_COLLAPSED, null).orEmpty().toSet())

    /** The keys of the sections the user has collapsed. */
    val collapsedSections: StateFlow<Set<String>> = _collapsedSections.asStateFlow()

    fun toggleSection(key: String) {
        val collapsed = _collapsedSections.value.let { if (key in it) it - key else it + key }
        prefs.edit { putStringSet(KEY_COLLAPSED, collapsed) }
        _collapsedSections.value = collapsed
    }

    private companion object {
        const val KEY_SORT = "host_list_sort"
        const val KEY_COLLAPSED = "host_list_collapsed_sections"
    }
}
