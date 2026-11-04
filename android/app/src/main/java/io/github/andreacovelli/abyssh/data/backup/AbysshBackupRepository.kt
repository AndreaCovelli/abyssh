package io.github.andreacovelli.abyssh.data.backup

import androidx.room.withTransaction
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.service.backup.AbysshBackupCodec
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.SshKey

class AbysshBackupRepository(
    private val db: AppDatabase,
) {
    /**
     * Throws [UnreadableSecretsException] rather than export saved secrets that can't
     * be opened, which would otherwise be written as empty.
     */
    suspend fun createEncryptedBackup(passphrase: CharArray): ByteArray {
        val payload = db.withTransaction {
            val unreadableKeys = db.sshKeyDao().unreadableNames()
            val unreadableHosts = db.hostProfileDao().unreadableNames()
            if (unreadableKeys.isNotEmpty() || unreadableHosts.isNotEmpty()) {
                throw UnreadableSecretsException(keys = unreadableKeys, hosts = unreadableHosts)
            }
            BackupPayload(
                keys = db.sshKeyDao().getAll().map(BackupSshKey::fromEntity),
                hosts = db.hostProfileDao().getAll().map(BackupHostProfile::fromEntity),
            )
        }
        return AbysshBackupCodec.encrypt(payload, passphrase)
    }

    fun readEncryptedBackup(
        bytes: ByteArray,
        passphrase: CharArray,
    ): BackupPayload = AbysshBackupCodec.decrypt(bytes, passphrase)

    suspend fun planImport(payload: BackupPayload): BackupImportPlan = AbysshBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = db.sshKeyDao().getAll(),
            existingHosts = db.hostProfileDao().getAll(),
        )

    suspend fun importPayload(payload: BackupPayload): BackupImportResult = db.withTransaction {
            val plan = AbysshBackupImportPlanner.planImport(
                payload = payload,
                existingKeys = db.sshKeyDao().getAll(),
                existingHosts = db.hostProfileDao().getAll(),
            )
            applyImportPlan(plan)
        }

    suspend fun importEncryptedBackup(
        bytes: ByteArray,
        passphrase: CharArray,
    ): BackupImportResult {
        val payload = readEncryptedBackup(bytes, passphrase)
        return importPayload(payload)
    }

    private suspend fun applyImportPlan(plan: BackupImportPlan): BackupImportResult {
        val insertedKeyIdsByActionIndex = mutableMapOf<Int, Long>()
        plan.keyActions.forEachIndexed { index, action ->
            if (action is KeyImportAction.Insert) {
                check(action.key.id == 0L) { "Imported SSH keys must not preserve exported ids" }
                val localId = db.sshKeyDao().insertForImport(action.key)
                insertedKeyIdsByActionIndex[index] = localId
            }
        }

        var importedHosts = 0
        plan.hostActions.forEach { action ->
            check(action.host.id == 0L) { "Imported hosts must not preserve exported ids" }
            val localKeyId = action.keyActionIndex?.let { insertedKeyIdsByActionIndex[it] }
                ?: action.host.keyId
            val host = action.host.copy(keyId = localKeyId)
            db.hostProfileDao().insertForImport(host)
            importedHosts += 1
        }

        return BackupImportResult(
            importedKeys = plan.keyInsertCount,
            reusedKeys = plan.keyReuseCount,
            importedHosts = importedHosts,
            renamedKeys = plan.renamedKeyCount,
            renamedHosts = plan.renamedHostCount,
            skippedHosts = plan.skippedHostCount,
        )
    }
}

/** Saved keys and hosts whose secrets can't be opened, named so the user can fix them. */
class UnreadableSecretsException(val keys: List<String>, val hosts: List<String>) : Exception("${keys.size} keys and ${hosts.size} hosts have secrets that can't be opened")

data class BackupImportResult(
    val importedKeys: Int,
    val reusedKeys: Int,
    val importedHosts: Int,
    val renamedKeys: Int,
    val renamedHosts: Int,
    val skippedHosts: Int = 0,
)

fun buildBackupPayload(
    keys: List<SshKey>,
    hosts: List<HostProfile>,
): BackupPayload = BackupPayload(
    keys = keys.map(BackupSshKey::fromEntity),
    hosts = hosts.map(BackupHostProfile::fromEntity),
)
