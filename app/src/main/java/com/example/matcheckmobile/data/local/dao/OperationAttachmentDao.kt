package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationAttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: OperationAttachmentEntity)

    @Query("SELECT * FROM operation_attachments WHERE localId = :id LIMIT 1")
    suspend fun findById(id: String): OperationAttachmentEntity?

    @Query("SELECT * FROM operation_attachments WHERE operationLocalId = :operationId ORDER BY createdAt")
    fun observeByOperation(operationId: String): Flow<List<OperationAttachmentEntity>>

    @Query("SELECT * FROM operation_attachments WHERE sessionLocalId = :sessionId ORDER BY createdAt")
    fun observeBySession(sessionId: String): Flow<List<OperationAttachmentEntity>>

    @Query("DELETE FROM operation_attachments WHERE localId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM operation_attachments WHERE uploadStatus IN (:statuses) ORDER BY createdAt")
    suspend fun findByStatuses(statuses: List<UploadStatus>): List<OperationAttachmentEntity>

    @Query("SELECT * FROM operation_attachments WHERE uploadStatus IN (:statuses) ORDER BY createdAt DESC")
    fun observeByStatuses(statuses: List<UploadStatus>): Flow<List<OperationAttachmentEntity>>

    @Query("SELECT COUNT(*) FROM operation_attachments WHERE uploadStatus IN (:statuses)")
    fun observeCountByStatuses(statuses: List<UploadStatus>): Flow<Int>

    @Query("UPDATE operation_attachments SET uploadStatus = :status, lastUploadError = :error WHERE localId = :id")
    suspend fun updateUploadStatus(id: String, status: UploadStatus, error: String?)

    @Query(
        "UPDATE operation_attachments SET uploadStatus = :status, remoteUrl = :remoteUrl, " +
            "lastUploadError = NULL WHERE localId = :id"
    )
    suspend fun markUploaded(id: String, status: UploadStatus, remoteUrl: String)

    /** Незагруженные legacy-вложения — для барьера при выходе. */
    @Query("SELECT COUNT(*) FROM operation_attachments WHERE uploadStatus IN (:statuses)")
    suspend fun countByStatuses(statuses: List<UploadStatus>): Int
}
