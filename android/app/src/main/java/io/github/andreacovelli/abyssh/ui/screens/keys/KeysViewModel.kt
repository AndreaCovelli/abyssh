package io.github.andreacovelli.abyssh.ui.screens.keys

import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.repository.HostRepository
import io.github.andreacovelli.abyssh.data.repository.ImportResult
import io.github.andreacovelli.abyssh.data.repository.KeyGenerationResult
import io.github.andreacovelli.abyssh.data.repository.RenameResult
import io.github.andreacovelli.abyssh.data.repository.SshKeyRepository
import io.github.andreacovelli.abyssh.model.SshKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeysViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(KeysViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return KeysViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val hostRepository = HostRepository(db.hostProfileDao())

    val state: StateFlow<KeysUiState> =
        combine(sshKeyRepository.observeAll(), hostRepository.observeKeyUsageCounts()) { keys, usage ->
                KeysUiState(
                    keys =
                        keys.map { key ->
                            KeyListItem(key = key, usedByServers = usage[key.id] ?: 0)
                        }
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = KeysUiState(),
            )

    private val _events = MutableSharedFlow<KeysEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun generate(nameHint: String, passphrase: String = "") {
        viewModelScope.launch {
            when (val result = sshKeyRepository.generate(nameHint, passphrase)) {
                is KeyGenerationResult.Success ->
                    _events.emit(KeysEvent.Generated(result.key.name))
                KeyGenerationResult.Failed ->
                    _events.emit(KeysEvent.GenerateFailed(UiText.Resource(R.string.keys_generate_failed)))
            }
        }
    }

    fun import(nameHint: String, privateKeyPem: String) {
        viewModelScope.launch {
            val event =
                when (val result = sshKeyRepository.import(nameHint, privateKeyPem)) {
                    is ImportResult.Success ->
                        KeysEvent.Imported(result.key.name, result.publicKeyDerived)
                    ImportResult.Blank -> KeysEvent.ImportFailed(UiText.Resource(R.string.keys_import_blank))
                    ImportResult.NotAPrivateKey ->
                        KeysEvent.ImportFailed(UiText.Resource(R.string.keys_import_not_private))
                }
            _events.emit(event)
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            val event =
                when (sshKeyRepository.rename(id, newName)) {
                    RenameResult.Success -> KeysEvent.Renamed
                    RenameResult.Blank -> KeysEvent.RenameFailed(UiText.Resource(R.string.keys_rename_blank))
                    RenameResult.NameTaken ->
                        KeysEvent.RenameFailed(UiText.Resource(R.string.keys_rename_duplicate, newName.trim()))
                }
            _events.emit(event)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            db.withTransaction {
                hostRepository.clearKeyReference(id)
                sshKeyRepository.deleteById(id)
            }
            _events.emit(KeysEvent.Deleted)
        }
    }
}

data class KeysUiState(val keys: List<KeyListItem> = emptyList())

data class KeyListItem(val key: SshKey, val usedByServers: Int)

sealed interface KeysEvent {
    data class Generated(val name: String) : KeysEvent

    data class Imported(val name: String, val publicKeyDerived: Boolean) : KeysEvent

    data class ImportFailed(val message: UiText) : KeysEvent

    data class GenerateFailed(val message: UiText) : KeysEvent

    data object Renamed : KeysEvent

    data class RenameFailed(val message: UiText) : KeysEvent

    data object Deleted : KeysEvent
}
