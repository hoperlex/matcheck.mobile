package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.RemoteCounterpartyEntity
import com.example.matcheckmobile.data.local.entity.RemoteMaterialEntity
import com.example.matcheckmobile.data.local.entity.RemoteSiteEntity
import com.example.matcheckmobile.data.local.entity.RemoteStatusEntity
import com.example.matcheckmobile.data.local.entity.RemoteUnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteCounterpartyDao {
    @Upsert
    suspend fun upsertAll(entities: List<RemoteCounterpartyEntity>)

    @Query("SELECT * FROM remote_counterparties ORDER BY name")
    fun observeAll(): Flow<List<RemoteCounterpartyEntity>>

    @Query("SELECT * FROM remote_counterparties WHERE id = :id")
    suspend fun findById(id: String): RemoteCounterpartyEntity?

    @Query("SELECT COUNT(*) FROM remote_counterparties")
    suspend fun count(): Int
}

@Dao
interface RemoteMaterialDao {
    @Upsert
    suspend fun upsertAll(entities: List<RemoteMaterialEntity>)

    @Query("SELECT * FROM remote_materials ORDER BY name")
    fun observeAll(): Flow<List<RemoteMaterialEntity>>

    @Query("SELECT * FROM remote_materials WHERE id = :id")
    suspend fun findById(id: String): RemoteMaterialEntity?

    @Query("SELECT COUNT(*) FROM remote_materials")
    suspend fun count(): Int
}

@Dao
interface RemoteSiteDao {
    @Upsert
    suspend fun upsertAll(entities: List<RemoteSiteEntity>)

    @Query("SELECT * FROM remote_sites WHERE isActive = 1 ORDER BY name")
    fun observeActive(): Flow<List<RemoteSiteEntity>>

    @Query("SELECT * FROM remote_sites WHERE id = :id")
    suspend fun findById(id: String): RemoteSiteEntity?

    @Query("SELECT COUNT(*) FROM remote_sites")
    suspend fun count(): Int
}

@Dao
interface RemoteStatusDao {
    @Upsert
    suspend fun upsertAll(entities: List<RemoteStatusEntity>)

    @Query("SELECT * FROM remote_statuses WHERE entityType = :type ORDER BY sortOrder")
    suspend fun listByType(type: String): List<RemoteStatusEntity>

    @Query("SELECT * FROM remote_statuses WHERE entityType = :type AND code = :code LIMIT 1")
    suspend fun find(type: String, code: String): RemoteStatusEntity?

    @Query("SELECT COUNT(*) FROM remote_statuses")
    suspend fun count(): Int
}

@Dao
interface RemoteUnitDao {
    @Upsert
    suspend fun upsertAll(entities: List<RemoteUnitEntity>)

    /** Активные единицы, по name. Используется dropdown'ом в модалке материалов. */
    @Query("SELECT * FROM remote_units WHERE isActive = 1 ORDER BY name")
    fun observeActive(): Flow<List<RemoteUnitEntity>>

    @Query("SELECT COUNT(*) FROM remote_units")
    suspend fun count(): Int
}
