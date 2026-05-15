package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MaterialOperationDao
import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.SyncQueueEntity
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SyncStatus
import com.example.matcheckmobile.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class OperationRepository(
    private val operationDao: MaterialOperationDao,
    private val attachmentDao: OperationAttachmentDao,
    private val syncQueueDao: SyncQueueDao,
) {
    fun observeAll(): Flow<List<MaterialOperationEntity>> = operationDao.observeAll()

    fun observeById(id: String): Flow<MaterialOperationEntity?> = operationDao.observeById(id)

    fun observeUnsyncedCount(): Flow<Int> = operationDao.observeCountFreeBySyncStatuses(UNSYNCED)

    fun observeUnsynced(): Flow<List<MaterialOperationEntity>> =
        operationDao.observeFreeBySyncStatuses(UNSYNCED)

    fun observeAttachments(operationId: String): Flow<List<OperationAttachmentEntity>> =
        attachmentDao.observeByOperation(operationId)

    suspend fun createOperation(
        type: OperationType,
        siteId: String,
        materialId: String?,
        materialNameRaw: String,
        quantity: Double,
        unit: String,
        userId: String,
        deviceId: String,
        vehicleNumber: String?,
        driverName: String?,
        comment: String?,
    ): MaterialOperationEntity {
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()
        val operation = MaterialOperationEntity(
            localId = localId,
            serverId = null,
            type = type,
            siteId = siteId,
            materialId = materialId,
            materialNameRaw = materialNameRaw,
            quantity = quantity,
            unit = unit,
            userId = userId,
            deviceId = deviceId,
            vehicleNumber = vehicleNumber,
            driverName = driverName,
            comment = comment,
            createdAtLocal = now,
            receivedAtServer = null,
            syncStatus = SyncStatus.PENDING,
            lastSyncError = null,
            idempotencyKey = UUID.randomUUID().toString(),
            sessionLocalId = null,
        )
        operationDao.upsert(operation)
        syncQueueDao.upsert(
            SyncQueueEntity(
                localId = UUID.randomUUID().toString(),
                targetType = SyncQueueEntity.TARGET_OPERATION,
                targetLocalId = localId,
                attempts = 0,
                lastAttemptAt = null,
                nextAttemptAt = now,
                lastError = null,
                createdAt = now,
            )
        )
        return operation
    }

    suspend fun attachPhoto(
        operationLocalId: String,
        localFilePath: String,
        attachmentType: AttachmentType,
    ): OperationAttachmentEntity {
        val now = System.currentTimeMillis()
        val attachmentId = UUID.randomUUID().toString()
        val attachment = OperationAttachmentEntity(
            localId = attachmentId,
            operationLocalId = operationLocalId,
            sessionLocalId = null,
            localFilePath = localFilePath,
            remoteUrl = null,
            attachmentType = attachmentType,
            uploadStatus = UploadStatus.PENDING_UPLOAD,
            createdAt = now,
            lastUploadError = null,
        )
        attachmentDao.upsert(attachment)
        syncQueueDao.upsert(
            SyncQueueEntity(
                localId = UUID.randomUUID().toString(),
                targetType = SyncQueueEntity.TARGET_ATTACHMENT,
                targetLocalId = attachmentId,
                attempts = 0,
                lastAttemptAt = null,
                nextAttemptAt = now,
                lastError = null,
                createdAt = now,
            )
        )
        return attachment
    }

    companion object {
        val UNSYNCED = listOf(SyncStatus.PENDING, SyncStatus.SYNCING, SyncStatus.ERROR)
    }
}
