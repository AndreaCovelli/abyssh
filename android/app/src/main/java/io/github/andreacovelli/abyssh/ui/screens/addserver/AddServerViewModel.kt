package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.model.toUiText

import io.github.andreacovelli.abyssh.model.UiTextException

import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.data.repository.HostRepository
import io.github.andreacovelli.abyssh.data.repository.KeyGenerationResult
import io.github.andreacovelli.abyssh.data.repository.SshKeyRepository
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.SshKey
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.ssh.HostKeyCheck
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Form for adding or editing a server. With [copy], [hostId] is only the starting
 * point: the form is prefilled from it and saving creates a new server.
 */
class AddServerViewModel(
    application: Application,
    private val hostId: Long?,
    private val copy: Boolean = false,
) : AndroidViewModel(application) {
    companion object {
        fun factory(application: Application, hostId: Long?, copy: Boolean = false): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId, copy) as T
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
    private val hostKeyStore =
        HostKeyStore(
            application.applicationContext.getSharedPreferences(
                HostKeyStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            )
        )

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _keys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _keys.asStateFlow()

    private val _errorMessage = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<UiText> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _keys.value = it } }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value =
                    AddServerForm(
                        id = if (copy) null else profile.id,
                        name = if (copy) "${profile.name} (copy)" else profile.name,
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
                    )
            }
        }
    }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host.filterNot { it.isWhitespace() })
    }

    fun updatePort(port: String) {
        _form.value = _form.value.copy(port = port)
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
                is KeyGenerationResult.Success ->
                    _form.value =
                        _form.value.copy(
                            keyId = result.key.id,
                            privateKeyPem = result.key.privateKeyPem,
                            publicKeyOpenSsh = result.key.publicKeyOpenSsh,
                        )
                KeyGenerationResult.Failed -> _errorMessage.emit(UiText.Resource(R.string.keys_generate_failed))
            }
        }
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod =
            when {
                transport != Transport.TailscaleSSH && current.authMethod == AuthMethod.None ->
                    AuthMethod.Password
                else -> current.authMethod
            }
        _form.value =
            current.copy(
                transport = transport,
                authMethod = nextAuthMethod,
                multiplexer =
                    current.multiplexer.takeIf {
                        transport != Transport.Mosh && it?.runtimeSupported == true
                    },
            )
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
        val current = _form.value
        _form.value =
            current.copy(
                multiplexer =
                    multiplexer?.takeIf {
                        current.transport != Transport.Mosh && it.runtimeSupported
                    }
            )
    }

    fun testConnection() {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        if (host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return
        _testState.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        viewModelScope.launch {
            var hostKeyError: UiText? = null
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val port = current.port.toIntOrNull() ?: 22
                        val policy = HostKeyPolicy { host, port, algorithm, keyBytes ->
                            when (
                                val result = hostKeyStore.check(host, port, algorithm, keyBytes)
                            ) {
                                is HostKeyCheck.Match -> true
                                is HostKeyCheck.Unknown -> {
                                    hostKeyError = UiText.Resource(R.string.add_server_host_key_unknown)
                                    false
                                }
                                is HostKeyCheck.Changed -> {
                                    hostKeyError = UiText.Resource(R.string.add_server_host_key_changed,
                                        result.previousFingerprint,
                                        result.fingerprint,
                                    )
                                    false
                                }
                            }
                        }
                        NativeSshService(hostKeyPolicy = policy).use { nativeSsh ->
                            if (!nativeSsh.isAvailable()) {
                                throw UiTextException(UiText.Resource(R.string.error_native_ssh_unavailable), "Native SSH unavailable")
                            }
                            nativeSsh.connect(
                                host = host,
                                port = port,
                                username = username,
                                authMethod = current.authMethod,
                                password =
                                    if (current.authMethod == AuthMethod.Password) {
                                        current.password
                                    } else {
                                        ""
                                    },
                                publicKeyOpenSsh = current.publicKeyOpenSsh,
                                privateKeyPem = current.privateKeyPem,
                                keyPassphrase = current.keyPassphrase,
                            )
                        }
                    }
                }
            _testState.value =
                if (result.isSuccess) {
                    ConnectionTestState(status = ConnectionTestStatus.Success)
                } else {
                    ConnectionTestState(
                        status = ConnectionTestStatus.Error,
                        message = hostKeyError
                            ?: result.exceptionOrNull()?.toUiText(UiText.Resource(R.string.terminal_connection_failed))
                            ?: UiText.Resource(R.string.terminal_connection_failed),
                    )
                }
        }
    }

    fun save(onComplete: () -> Unit) {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        val port = current.port.toIntOrNull() ?: 22
        if (!current.canSave() || host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return

        viewModelScope.launch {
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
                    multiplexer =
                        current.multiplexer.takeIf {
                            current.transport != Transport.Mosh && it?.runtimeSupported == true
                        },
                )
            hostRepository.upsert(profile)
            val stale = if (current.id != null) applyToOpenTabs(profile) else emptyList()
            if (stale.isEmpty()) {
                onComplete()
            } else {
                _pendingReconnect.value = PendingReconnect(stale, onComplete)
            }
        }
    }

    /** Open tabs of this host still running on the settings it had before the save. */
    class PendingReconnect(val tabs: List<TabSession>, val onDone: () -> Unit)

    private val _pendingReconnect = MutableStateFlow<PendingReconnect?>(null)
    val pendingReconnect: StateFlow<PendingReconnect?> = _pendingReconnect.asStateFlow()

    private suspend fun applyToOpenTabs(profile: HostProfile): List<TabSession> {
        if (sessionRepository.tabsForHost(profile.id).isEmpty()) return emptyList()
        val key = profile.keyId?.let { sshKeyRepository.getById(it) }
        val spec = TabSpec.fromHostProfile(
            host = profile,
            publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
            privateKeyPem = key?.privateKeyPem.orEmpty(),
        )
        return sessionRepository.applyHostUpdate(spec)
    }

    fun finishPendingReconnect(reconnect: Boolean) {
        val pending = _pendingReconnect.value ?: return
        _pendingReconnect.value = null
        if (reconnect) sessionRepository.reconnectTabs(pending.tabs)
        pending.onDone()
    }
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
)

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank()) return false
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
