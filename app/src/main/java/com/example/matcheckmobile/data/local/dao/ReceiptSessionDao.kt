package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ReceiptSessionEntity)

    @Query("SELECT * FROM receipt_sessions WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): ReceiptSessionEntity?

    @Query("SELECT * FROM receipt_sessions WHERE localId = :id LIMIT 1")
    fun observeById(id: String): Flow<ReceiptSessionEntity?>

    @Query("SELECT * FROM receipt_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ReceiptSessionEntity>>

    @Query("SELECT * FROM receipt_sessions WHERE syncStatus IN (:statuses) ORDER BY startedAt")
    suspend fun findBySyncStatuses(statuses: List<SyncStatus>): List<ReceiptSessionEntity>

    @Query("SELECT COUNT(*) FROM receipt_sessions WHERE syncStatus IN (:statuses)")
    fun observeCountBySyncStatuses(statuses: List<SyncStatus>): Flow<Int>

    @Query("SELECT * FROM receipt_sessions WHERE syncStatus IN (:statuses) ORDER BY startedAt DESC")
    fun observeBySyncStatuses(statuses: List<SyncStatus>): Flow<List<ReceiptSessionEntity>>

    @Query(
        "SELECT * FROM receipt_sessions " +
            "WHERE kind = 'RECEIPT' AND syncStatus = 'LOCAL_SAVED' " +
            "ORDER BY COALESCE(finalizedAt, startedAt) DESC"
    )
    fun observeLocalSavedReceipts(): Flow<List<ReceiptSessionEntity>>

    @Query("UPDATE receipt_sessions SET syncStatus = :status, lastSyncError = :error WHERE localId = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus, error: String?)

    @Query(
        "UPDATE receipt_sessions SET syncStatus = :status, serverId = :serverId, lastSyncError = NULL " +
            "WHERE localId = :id"
    )
    suspend fun markSynced(id: String, status: SyncStatus, serverId: String?)

    @Query("DELETE FROM receipt_sessions WHERE localId = :id")
    suspend fun deleteById(id: String)
}
