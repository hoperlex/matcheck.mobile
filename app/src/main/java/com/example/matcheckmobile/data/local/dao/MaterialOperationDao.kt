package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MaterialOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: MaterialOperationEntity)

    @Query("SELECT * FROM material_operations WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): MaterialOperationEntity?

    @Query("SELECT * FROM material_operations WHERE localId = :id LIMIT 1")
    fun observeById(id: String): Flow<MaterialOperationEntity?>

    @Query("SELECT * FROM material_operations ORDER BY createdAtLocal DESC")
    fun observeAll(): Flow<List<MaterialOperationEntity>>

    @Query("SELECT * FROM material_operations WHERE syncStatus IN (:statuses) ORDER BY createdAtLocal ASC")
    suspend fun findBySyncStatuses(statuses: List<SyncStatus>): List<MaterialOperationEntity>

    @Query("SELECT * FROM material_operations WHERE syncStatus IN (:statuses) ORDER BY createdAtLocal DESC")
    fun observeBySyncStatuses(statuses: List<SyncStatus>): Flow<List<MaterialOperationEntity>>

    @Query("SELECT COUNT(*) FROM material_operations WHERE syncStatus IN (:statuses)")
    fun observeCountBySyncStatuses(statuses: List<SyncStatus>): Flow<Int>

    @Query("UPDATE material_operations SET syncStatus = :status, lastSyncError = :error WHERE localId = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus, error: String?)

    @Query(
        "UPDATE material_operations SET syncStatus = :status, serverId = :serverId, " +
            "receivedAtServer = :receivedAtServer, lastSyncError = NULL WHERE localId = :id"
    )
    suspend fun markSynced(id: String, status: SyncStatus, serverId: String?, receivedAtServer: Long)
}
