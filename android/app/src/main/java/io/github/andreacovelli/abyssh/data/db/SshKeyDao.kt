package io.github.andreacovelli.abyssh.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.andreacovelli.abyssh.model.SshKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Keys as callers see them: the private key is sealed only in the table. */
@Dao
abstract class SshKeyDao(private val db: AppDatabase) {
    fun observeAll(): Flow<List<SshKey>> = observeAllSealed().map { keys -> keys.map(::open) }

    suspend fun getById(id: Long): SshKey? = getByIdSealed(id)?.let(::open)

    suspend fun getAll(): List<SshKey> = getAllSealed().map(::open)

    suspend fun insert(key: SshKey): Long = insertSealed(seal(key))

    suspend fun insertForImport(key: SshKey): Long = insertForImportSealed(seal(key))

    @Query("UPDATE ssh_keys SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM ssh_keys WHERE name = :name AND id != :excludeId)")
    abstract suspend fun nameExists(name: String, excludeId: Long): Boolean

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("SELECT * FROM ssh_keys ORDER BY name")
    protected abstract fun observeAllSealed(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    protected abstract suspend fun getByIdSealed(id: Long): SshKey?

    @Query("SELECT * FROM ssh_keys ORDER BY name")
    protected abstract suspend fun getAllSealed(): List<SshKey>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSealed(key: SshKey): Long

    @Insert protected abstract suspend fun insertForImportSealed(key: SshKey): Long

    private fun seal(key: SshKey): SshKey = key.copy(privateKeyPem = db.secretBox.seal(key.privateKeyPem))

    private fun open(key: SshKey): SshKey = key.copy(privateKeyPem = db.secretBox.openOrEmpty(key.privateKeyPem))
}
