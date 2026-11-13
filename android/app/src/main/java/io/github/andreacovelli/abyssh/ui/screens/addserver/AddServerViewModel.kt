package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess



import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.db.SavedSecrets
import io.github.andreacovelli.abyssh.data.repository.HostRepository
import io.github.andreacovelli.abyssh.data.repository.KeyGenerationResult
import io.github.andreacovelli.abyssh.data.repository.SshKeyRepository
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.DEFAULT_KEEPALIVE_INTERVAL_SECONDS
import io.github.andreacovelli.abyssh.model.MAX_KEEPALIVE_INTERVAL_SECONDS
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.QuickConnectTarget
import io.github.andreacovelli.abyssh.model.SshKey
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import io.github.andreacovelli.abyssh.service.terminal.HostKeyPrompt
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Form for adding or editing a server. With [copy], [hostId] is only the starting
 * point: the form is prefilled from it and saving creates a new server. [prefill] starts
 * a new server from a quick connect address.
 */
class AddServerViewModel(
    application: Application,
    private val hostId: Long?,
    private val copy: Boolean = false,
    private val prefill: QuickConnectTarget? = null,
) : AndroidViewModel(application) {
    companion object {
        fun factory(
            application: Application,
            hostId: Long?,
            copy: Boolean = false,
            prefill: QuickConnectTarget? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId, copy, prefill) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    val isCopy: Boolean get() = copy

    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val localNetwork = LocalNetworkAccess(application)
    private val hostKeyStore =
        HostKeyStore(
            application.applicationContext.getSharedPreferences(
                HostKeyStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            )
        )

    private val _form = MutableStateFlow(prefill?.let(AddServerForm::from) ?: AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val tester = ConnectionTester(viewModelScope, hostKeyStore, localNetwork)
    val testState: StateFlow<ConnectionTestState> = tester.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = tester.hostKeyPrompt

    private val _saving = MutableStateFlow(false)

    /** True from the first Save until the editor closes, so a second tap saves nothing. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val generatedKey = UnsavedGeneratedKey(sshKeyRepository)

    private val _keys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _keys.asStateFlow()

    private val _errorMessage = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<UiText> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _keys.value = it } }
        // A test result describes the form it ran on, so any edit clears it.
        viewModelScope.launch { _form.drop(1).collect { tester.reset() } }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value =
                    AddServerForm(
                        id = if (copy) null else profile.id,
                        name = if (copy) application.getString(R.string.add_server_copy_name, profile.name) else profile.name,
                        host = profile.host,
                        port = profile.port.toString(),
                        username = profile.username,
                        password = profile.password,
                        keyId = profile.keyId,
                        privateKeyPem = key?.privateKeyPem ?: "",
                        publicKeyOpenSsh = key?.publicKeyOpenSsh ?: "",
                        keyPassphrase = profile.keyPassphrase,
                        transport = profile.transport,
                        // Only Tailscale SSH can connect without credentials.
                        authMethod =
                            profile.authMethod.takeUnless {
                                it == AuthMethod.None && profile.transport != Transport.TailscaleSSH
                            } ?: AuthMethod.Password,
                        requireAuthOnConnect = profile.requireAuthOnConnect,
                        postConnectCommand = profile.postConnectCommand.orEmpty(),
                        multiplexer = profile.multiplexer,
                        tmuxControlMode = profile.tmuxControlMode,
                        chooseSessionOnConnect = profile.chooseSessionOnConnect,
                        keepaliveInterval = profile.keepaliveIntervalSeconds.toString(),
                        groupName = profile.groupName,
                        unreadableSecrets = hostRepository.unreadableSecrets(hostId),
                    )
            }
        }
    }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateGroup(groupName: String) {
        _form.value = _form.value.copy(groupName = groupName)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host.filterNot { it.isWhitespace() })
    }

    fun updatePort(port: String) {
        _form.value = _form.value.copy(port = port.filter(Char::isDigit).take(PORT_FIELD_DIGITS))
    }

    fun updateUsername(username: String) {
        _form.value = _form.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _form.value = _form.value.copy(password = password)
    }

    fun updateKeyPassphrase(passphrase: String) {
        _form.value = _form.value.copy(keyPassphrase = passphrase)
    }

    /** Marks unreadable saved secrets to erase when the form is saved. */
    fun clearUnreadable(secrets: SavedSecrets) {
        _form.value = _form.value.withClearedSecrets(secrets)
    }

    fun selectStoredKey(keyId: Long?) {
        viewModelScope.launch {
            val selected =
                keyId?.let { id ->
                    keys.value.firstOrNull { it.id == id } ?: sshKeyRepository.getById(id)
                }
            _form.value =
                _form.value.copy(
                    keyId = keyId,
                    privateKeyPem = selected?.privateKeyPem ?: "",
                    publicKeyOpenSsh = selected?.publicKeyOpenSsh ?: "",
                    keyPassphrase = "",
                )
        }
    }

    fun generateKey(nameHint: String = "") {
        viewModelScope.launch {
            val current = _form.value
            val baseName = if (nameHint.isNotBlank()) nameHint else current.name
            when (val result = sshKeyRepository.generate(baseName, current.keyPassphrase)) {
                is KeyGenerationResult.Success -> {
                    generatedKey.replaceWith(result.key.id)
                    _form.value =
                        _form.value.copy(
                            keyId = result.key.id,
                            privateKeyPem = result.key.privateKeyPem,
                            publicKeyOpenSsh = result.key.publicKeyOpenSsh,
                        )
                }
                KeyGenerationResult.Failed -> _errorMessage.emit(UiText.Resource(R.string.keys_generate_failed))
            }
        }
    }

    fun updateTransport(transport: Transport) {
        _form.value = _form.value.withTransport(transport)
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val current = _form.value
        if (authMethod == AuthMethod.None) {
            _form.value =
                current.copy(transport = Transport.TailscaleSSH, authMethod = AuthMethod.None)
            return
        }
        _form.value = current.copy(authMethod = authMethod)
    }

    /** Switches between a key with and without a passphrase; turning it off clears the passphrase. */
    fun setKeyPassphraseEnabled(enabled: Boolean) {
        if (enabled) {
            updateAuthMethod(AuthMethod.KeyWithPassphrase)
        } else {
            _form.value = _form.value.copy(authMethod = AuthMethod.Key, keyPassphrase = "")
        }
    }

    fun updateRequireAuthOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(requireAuthOnConnect = enabled)
    }

    fun updatePostConnectCommand(command: String) {
        _form.value = _form.value.copy(postConnectCommand = command)
    }

    fun updateMultiplexer(multiplexer: MultiplexerType?) {
        _form.value = _form.value.withMultiplexer(multiplexer)
    }

    fun updateTmuxControlMode(enabled: Boolean) {
        _form.value = _form.value.copy(tmuxControlMode = enabled)
    }

    fun updateKeepaliveInterval(seconds: String) {
        _form.value = _form.value.copy(keepaliveInterval = seconds.filter(Char::isDigit).take(KEEPALIVE_FIELD_DIGITS))
    }

    fun updateChooseSessionOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(chooseSessionOnConnect = enabled)
    }

    fun testConnection() = tester.test(_form.value)

    fun onHostKeyDecision(accept: Boolean) = tester.onHostKeyDecision(accept)

    /** Saves the form, then calls [onComplete] with the saved host, unless it asks about open tabs first. */
    fun save(onComplete: (HostProfile) -> Unit) {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        val port = current.portNumber ?: return
        if (!current.canSave() || host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return
        if (_saving.value) return
        _saving.value = true

        viewModelScope.launch {
            // Set from the server list and by connecting, so the form may be behind on these.
            val stored = current.id?.let { hostRepository.getById(it) }
            val profile =
                HostProfile(
                    id = current.id ?: 0L,
                    name = current.name.trim(),
                    host = host,
                    port = port,
                    username = username,
                    password = current.password,
                    keyId = current.keyId,
                    keyPassphrase = current.keyPassphrase,
                    transport = current.transport,
                    authMethod = current.authMethod,
                    requireAuthOnConnect = current.requireAuthOnConnect,
                    postConnectCommand = current.postConnectCommand.trim().ifBlank { null },
                    multiplexer = current.effectiveMultiplexer,
                    tmuxControlMode = current.tmuxControlMode,
                    chooseSessionOnConnect = current.chooseSessionOnConnect,
                    keepaliveIntervalSeconds = current.keepaliveIntervalSeconds,
                    groupName = current.groupName.trim(),
                    favorite = stored?.favorite ?: false,
                    lastConnectedAtEpochMs = stored?.lastConnectedAtEpochMs ?: 0L,
                )
            var savedId: Long? = null
            try {
                savedId = hostRepository.upsert(profile, discard = current.clearedSecrets)
            } finally {
                if (savedId == null) _saving.value = false
            }
            generatedKey.keepIfUsed(profile.keyId)
            val saved = profile.copy(id = savedId ?: profile.id)
            val stale = if (current.id != null) sessionRepository.applyToOpenTabs(profile, sshKeyRepository) else emptyList()
            if (stale.isEmpty()) {
                onComplete(saved)
            } else {
                _pendingReconnect.value = PendingReconnect(stale) { onComplete(saved) }
            }
        }
    }

    /** Open tabs of this host still running on the settings it had before the save. */
    class PendingReconnect(val tabs: List<TabSession>, val onDone: () -> Unit)

    private val _pendingReconnect = MutableStateFlow<PendingReconnect?>(null)
    val pendingReconnect: StateFlow<PendingReconnect?> = _pendingReconnect.asStateFlow()

    override fun onCleared() {
        tester.close()
        generatedKey.discard()
    }

    fun finishPendingReconnect(reconnect: Boolean) {
        val pending = _pendingReconnect.value ?: return
        _pendingReconnect.value = null
        if (reconnect) sessionRepository.reconnectTabs(pending.tabs)
        pending.onDone()
    }
}

/** Gives this host's open tabs the saved [profile], and returns those that must reconnect to use it. */
private suspend fun TerminalSessionRepository.applyToOpenTabs(profile: HostProfile, keys: SshKeyRepository): List<TabSession> {
    if (tabsForHost(profile.id).isEmpty()) return emptyList()
    val key = profile.keyId?.let { keys.getById(it) }
    val spec = TabSpec.fromHostProfile(
        host = profile,
        publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
        privateKeyPem = key?.privateKeyPem.orEmpty(),
    )
    return applyHostUpdate(spec)
}

data class AddServerForm(
    val id: Long? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyId: Long? = null,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String = "",
    val multiplexer: MultiplexerType? = null,
    val tmuxControlMode: Boolean = false,
    val chooseSessionOnConnect: Boolean = false,
    val keepaliveInterval: String = DEFAULT_KEEPALIVE_INTERVAL_SECONDS.toString(),
    val groupName: String = "",
    // The empty password and keyPassphrase above don't mean the host has none of these.
    val unreadableSecrets: SavedSecrets = SavedSecrets(),
    val clearedSecrets: SavedSecrets = SavedSecrets(),
) {
    companion object {
        /** A new server for a quick connect address, named after its host. */
        fun from(target: QuickConnectTarget): AddServerForm =
            AddServerForm(name = target.host, host = target.host, port = target.port.toString(), username = target.username)
    }
}

/** The unreadable saved secrets the form hasn't replaced or cleared, which it reports to the user. */
val AddServerForm.unreadableNotices: SavedSecrets
    get() = SavedSecrets(
        password = unreadableSecrets.password && !clearedSecrets.password && password.isEmpty(),
        keyPassphrase = unreadableSecrets.keyPassphrase && !clearedSecrets.keyPassphrase && keyPassphrase.isEmpty(),
    )

fun AddServerForm.withClearedSecrets(secrets: SavedSecrets): AddServerForm = copy(
    clearedSecrets =
        SavedSecrets(
            password = clearedSecrets.password || secrets.password,
            keyPassphrase = clearedSecrets.keyPassphrase || secrets.keyPassphrase,
        ),
)

private const val DEFAULT_SSH_PORT = 22
private const val MAX_PORT = 65535

// Enough digits for MAX_PORT.
private const val PORT_FIELD_DIGITS = 5

/** The port to connect to: the typed one when it is 1 to 65535, 22 when blank, otherwise null. */
val AddServerForm.portNumber: Int?
    get() = if (port.isBlank()) DEFAULT_SSH_PORT else port.toIntOrNull()?.takeIf { it in 1..MAX_PORT }

// Enough digits for MAX_KEEPALIVE_INTERVAL_SECONDS.
private const val KEEPALIVE_FIELD_DIGITS = 5

/** The keepalive interval to save: the typed seconds, at most a day, or the default when blank. */
val AddServerForm.keepaliveIntervalSeconds: Int
    get() = keepaliveInterval.toIntOrNull()?.coerceIn(0, MAX_KEEPALIVE_INTERVAL_SECONDS)
        ?: DEFAULT_KEEPALIVE_INTERVAL_SECONDS

/** The multiplexer the host uses: the chosen one, unless Abyssh can't run it yet. */
val AddServerForm.effectiveMultiplexer: MultiplexerType?
    get() = multiplexer?.takeIf { it.runtimeSupported }

/** Switches the transport. */
fun AddServerForm.withTransport(transport: Transport): AddServerForm = copy(
    transport = transport,
    // Only Tailscale SSH can connect without credentials.
    authMethod =
        authMethod.takeUnless { it == AuthMethod.None && transport != Transport.TailscaleSSH }
            ?: AuthMethod.Password,
)

/** Chooses a multiplexer, or none for one Abyssh can't run yet. */
fun AddServerForm.withMultiplexer(multiplexer: MultiplexerType?): AddServerForm =
    copy(multiplexer = multiplexer?.takeIf { it.runtimeSupported })

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank() || portNumber == null) return false
    return when (authMethod) {
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> keyId != null && privateKeyPem.isNotBlank()
        else -> true
    }
}

enum class ConnectionTestStatus {
    Idle,
    Running,
    Success,
    Error,
}

data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val message: UiText? = null,
)
