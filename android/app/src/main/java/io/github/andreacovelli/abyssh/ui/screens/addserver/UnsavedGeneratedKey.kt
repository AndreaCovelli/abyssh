package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.data.repository.SshKeyRepository
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** The key "Generate" made in the form, deleted when the form closes unless a saved host uses it. */
internal class UnsavedGeneratedKey(private val keys: SshKeyRepository) {
    private var id: Long? = null

    /** Deletes the key generated earlier, which nothing refers to, and tracks [keyId] instead. */
    fun replaceWith(keyId: Long) {
        discard()
        id = keyId
    }

    /** Stops tracking the key if [savedKeyId], the key of the host just saved, is that key. */
    fun keepIfUsed(savedKeyId: Long?) {
        if (savedKeyId == id) id = null
    }

    // No saved host uses the key, so nothing refers to it. It runs outside viewModelScope,
    // which onCleared has already cancelled.
    @OptIn(DelicateCoroutinesApi::class)
    fun discard() {
        val unsaved = id ?: return
        id = null
        GlobalScope.launch(Dispatchers.IO) { keys.deleteById(unsaved) }
    }
}
