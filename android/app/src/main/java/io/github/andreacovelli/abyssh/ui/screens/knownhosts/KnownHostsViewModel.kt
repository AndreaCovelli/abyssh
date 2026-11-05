package io.github.andreacovelli.abyssh.ui.screens.knownhosts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import io.github.andreacovelli.abyssh.service.ssh.KnownHostKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KnownHostsViewModel(application: Application) : AndroidViewModel(application) {
    private val store =
        HostKeyStore(application.getSharedPreferences(HostKeyStore.PREFS_NAME, Application.MODE_PRIVATE))

    private val _keys = MutableStateFlow(store.knownHostKeys())
    val keys: StateFlow<List<KnownHostKey>> = _keys.asStateFlow()

    fun forget(key: KnownHostKey) {
        store.forget(key.host, key.port, key.algorithm)
        _keys.value = store.knownHostKeys()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(KnownHostsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return KnownHostsViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
