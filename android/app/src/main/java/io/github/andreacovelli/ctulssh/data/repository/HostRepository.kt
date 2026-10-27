package io.github.andreacovelli.ctulssh.data.repository

import io.github.andreacovelli.ctulssh.data.db.HostProfileDao
import io.github.andreacovelli.ctulssh.model.HostProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HostRepository(private val dao: HostProfileDao) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll()

    fun observeKeyUsageCounts(): Flow<Map<Long, Int>> =
        dao.observeKeyUsage().map { rows -> rows.associate { it.keyId to it.count } }

    suspend fun getById(id: Long): HostProfile? = dao.getById(id)

    suspend fun upsert(profile: HostProfile): Long = dao.insert(profile)

    suspend fun delete(profile: HostProfile) = dao.delete(profile)

    suspend fun clearKeyReference(keyId: Long) = dao.clearKeyReference(keyId)
}
