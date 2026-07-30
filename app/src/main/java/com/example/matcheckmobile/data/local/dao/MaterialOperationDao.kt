package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MaterialOperationDao {
    /**
     * INSERT-or-UPDATE (без DELETE). REPLACE сносил бы дочерние operation_attachments
     * через FK CASCADE — поэтому только @Upsert.
     */
    @Upsert
    suspend fun upsert(operation: MaterialOperationEntity)

    @Query("SELECT * FROM material_operations WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): MaterialOperationEntity?

    @Query("SELECT * FROM material_operations WHERE localId = :id LIMIT 1")
    fun observeById(id: String): Flow<MaterialOperationEntity?>

    @Query("SELECT * FROM material_operations ORDER BY createdAtLocal DESC")
    fun observeAll(): Flow<List<MaterialOperationEntity>>

    @Query(
        "SELECT * FROM material_operations WHERE syncStatus IN (:statuses) " +
            "AND sessionLocalId IS NULL ORDER BY createdAtLocal ASC"
    )
    suspend fun findFreeBySyncStatuses(statuses: List<SyncStatus>): List<MaterialOperationEntity>

    @Query("SELECT * FROM material_operations WHERE sessionLocalId = :sessionId ORDER BY createdAtLocal")
    suspend fun findBySession(sessionId: String): List<MaterialOperationEntity>

    @Query("SELECT * FROM material_operations WHERE sessionLocalId = :sessionId ORDER BY createdAtLocal")
    fun observeBySession(sessionId: String): Flow<List<MaterialOperationEntity>>

    @Query("DELETE FROM material_operations WHERE localId = :id")
    suspend fun deleteById(id: String)

    @Query(
        "SELECT * FROM material_operations WHERE syncStatus IN (:statuses) " +
            "AND sessionLocalId IS NULL ORDER BY createdAtLocal DESC"
    )
    fun observeFreeBySyncStatuses(statuses: List<SyncStatus>): Flow<List<MaterialOperationEntity>>

    @Query(
        "SELECT COUNT(*) FROM material_operations WHERE syncStatus IN (:statuses) " +
            "AND sessionLocalId IS NULL"
    )
    fun observeCountFreeBySyncStatuses(statuses: List<SyncStatus>): Flow<Int>

    @Query("UPDATE material_operations SET syncStatus = :status, lastSyncError = :error WHERE localId = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus, error: String?)

    @Query(
        "UPDATE material_operations SET syncStatus = :status, serverId = :serverId, " +
            "receivedAtServer = :receivedAtServer, lastSyncError = NULL WHERE localId = :id"
    )
    suspend fun markSynced(id: String, status: SyncStatus, serverId: String?, receivedAtServer: Long)

    /** Незасинканные свободные legacy-операции — для барьера при выходе. */
    @Query(
        "SELECT COUNT(*) FROM material_operations WHERE syncStatus IN (:statuses) " +
            "AND sessionLocalId IS NULL"
    )
    suspend fun countFreeBySyncStatuses(statuses: List<SyncStatus>): Int
}
