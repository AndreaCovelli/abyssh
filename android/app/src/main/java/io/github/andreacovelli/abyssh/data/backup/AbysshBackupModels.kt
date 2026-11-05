package io.github.andreacovelli.abyssh.data.backup

import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.DEFAULT_KEEPALIVE_INTERVAL_SECONDS
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.SshKey
import io.github.andreacovelli.abyssh.model.Transport

const val ABYSSH_BACKUP_EXTENSION = "abyssh-backup"
const val ABYSSH_BACKUP_MIME_TYPE = "application/octet-stream"

class BackupFormatException(message: String) : Exception(message)

class InvalidBackupPassphraseException : Exception("Invalid backup passphrase")

data class BackupPayload(
    val keys: List<BackupSshKey>,
    val hosts: List<BackupHostProfile>,
) {
    fun toEntities(): Pair<List<SshKey>, List<HostProfile>> =
        keys.map { it.toEntity() } to hosts.map { it.toEntity() }
}

data class BackupSshKey(
    val id: Long,
    val name: String,
    val algorithm: String,
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val createdAtEpochMs: Long,
) {
    fun toEntity(idOverride: Long = id): SshKey = SshKey(
        id = idOverride,
        name = name,
        algorithm = algorithm,
        privateKeyPem = privateKeyPem,
        publicKeyOpenSsh = publicKeyOpenSsh,
        createdAtEpochMs = createdAtEpochMs,
    )

    /** Leaves out the private key, so logging a backup can't leak it. */
    override fun toString(): String = "BackupSshKey(id=$id, name=$name, algorithm=$algorithm)"

    companion object {
        fun fromEntity(key: SshKey): BackupSshKey = BackupSshKey(
            id = key.id,
            name = key.name,
            algorithm = key.algorithm,
            privateKeyPem = key.privateKeyPem,
            publicKeyOpenSsh = key.publicKeyOpenSsh,
            createdAtEpochMs = key.createdAtEpochMs,
        )
    }
}

data class BackupHostProfile(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val keyId: Long?,
    val keyPassphrase: String,
    val transport: Transport,
    val authMethod: AuthMethod,
    val requireAuthOnConnect: Boolean,
    val postConnectCommand: String?,
    val multiplexer: MultiplexerType?,
    val tmuxControlMode: Boolean = false,
    val chooseSessionOnConnect: Boolean = false,
    val keepaliveIntervalSeconds: Int = DEFAULT_KEEPALIVE_INTERVAL_SECONDS,
    val groupName: String = "",
    val favorite: Boolean = false,
) {
    fun toEntity(
        idOverride: Long = id,
        keyIdOverride: Long? = keyId,
    ): HostProfile = HostProfile(
        id = idOverride,
        name = name,
        host = host,
        port = port,
        username = username,
        password = password,
        keyId = keyIdOverride,
        keyPassphrase = keyPassphrase,
        transport = transport,
        authMethod = authMethod,
        requireAuthOnConnect = requireAuthOnConnect,
        postConnectCommand = postConnectCommand,
        multiplexer = multiplexer,
        tmuxControlMode = tmuxControlMode,
        chooseSessionOnConnect = chooseSessionOnConnect,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
        groupName = groupName,
        favorite = favorite,
    )

    /** Leaves out the password and passphrase, so logging a backup can't leak them. */
    override fun toString(): String =
        "BackupHostProfile(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "keyId=$keyId, transport=$transport, authMethod=$authMethod)"

    companion object {
        fun fromEntity(host: HostProfile): BackupHostProfile = BackupHostProfile(
            id = host.id,
            name = host.name,
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.password,
            keyId = host.keyId,
            keyPassphrase = host.keyPassphrase,
            transport = host.transport,
            authMethod = host.authMethod,
            requireAuthOnConnect = host.requireAuthOnConnect,
            postConnectCommand = host.postConnectCommand,
            multiplexer = host.multiplexer,
            tmuxControlMode = host.tmuxControlMode,
            chooseSessionOnConnect = host.chooseSessionOnConnect,
            keepaliveIntervalSeconds = host.keepaliveIntervalSeconds,
            groupName = host.groupName,
            favorite = host.favorite,
        )
    }
}
