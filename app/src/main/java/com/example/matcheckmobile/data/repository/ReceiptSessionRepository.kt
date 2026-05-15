package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MaterialOperationDao
import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.dao.ReceiptSessionDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SessionKind
import com.example.matcheckmobile.domain.model.SyncStatus
import com.example.matcheckmobile.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ReceiptSessionRepository(
    private val sessionDao: ReceiptSessionDao,
    private val operationDao: MaterialOperationDao,
    private val attachmentDao: OperationAttachmentDao,
    @Suppress("unused") private val syncQueueDao: SyncQueueDao,
) {
    fun observeById(id: String): Flow<ReceiptSessionEntity?> = sessionDao.observeById(id)

    fun observeItems(sessionId: String): Flow<List<MaterialOperationEntity>> =
        operationDao.observeBySession(sessionId)

    fun observeSessionAttachments(sessionId: String): Flow<List<OperationAttachmentEntity>> =
        attachmentDao.observeBySession(sessionId)

    fun observeLocalSavedReceipts(): Flow<List<ReceiptSessionEntity>> =
        sessionDao.observeLocalSavedReceipts()

    suspend fun findById(id: String): ReceiptSessionEntity? = sessionDao.findById(id)

    suspend fun findItems(sessionId: String): List<MaterialOperationEntity> =
        operationDao.findBySession(sessionId)

    suspend fun createDraft(
        kind: SessionKind,
        siteId: String,
        userId: String,
        deviceId: String,
        supplierLocalId: String?,
        contractorLocalId: String?,
        vehicleNumber: String,
        vehicleTypeCode: String?,
        volumeM3: Double?,
        massKg: Double?,
        sourceDocumentLocalId: String?,
        comment: String?,
    ): ReceiptSessionEntity {
        val now = System.currentTimeMillis()
        val session = ReceiptSessionEntity(
            localId = UUID.randomUUID().toString(),
            serverId = null,
            kind = kind,
            siteId = siteId,
            userId = userId,
            deviceId = deviceId,
            supplierLocalId = supplierLocalId,
            contractorLocalId = contractorLocalId,
            vehicleNumber = vehicleNumber,
            vehicleTypeCode = vehicleTypeCode,
            volumeM3 = volumeM3,
            massKg = massKg,
            sourceDocumentLocalId = sourceDocumentLocalId,
            comment = comment,
            startedAt = now,
            finalizedAt = null,
            completedAt = null,
            confirmedByMol = false,
            syncStatus = SyncStatus.DRAFT,
            lastSyncError = null,
            idempotencyKey = UUID.randomUUID().toString(),
        )
        sessionDao.upsert(session)
        return session
    }

    suspend fun updateDraftHeader(
        sessionId: String,
        siteId: String,
        supplierLocalId: String?,
        contractorLocalId: String?,
        vehicleNumber: String,
        vehicleTypeCode: String?,
        volumeM3: Double?,
        massKg: Double?,
        sourceDocumentLocalId: String?,
        comment: String?,
        confirmedByMol: Boolean,
    ) {
        val existing = sessionDao.findById(sessionId) ?: return
        if (!existing.isEditable()) return
        sessionDao.upsert(
            existing.copy(
                siteId = siteId,
                supplierLocalId = supplierLocalId,
                contractorLocalId = contractorLocalId,
                vehicleNumber = vehicleNumber,
                vehicleTypeCode = vehicleTypeCode,
                volumeM3 = volumeM3,
                massKg = massKg,
                sourceDocumentLocalId = sourceDocumentLocalId,
                comment = comment,
                confirmedByMol = confirmedByMol,
            )
        )
    }

    suspend fun addSessionPhoto(sessionId: String, localFilePath: String) {
        val session = sessionDao.findById(sessionId) ?: return
        if (!session.isEditable()) return
        attachmentDao.upsert(
            OperationAttachmentEntity(
                localId = UUID.randomUUID().toString(),
                operationLocalId = null,
                sessionLocalId = sessionId,
                localFilePath = localFilePath,
                remoteUrl = null,
                attachmentType = AttachmentType.CARGO,
                uploadStatus = UploadStatus.PENDING_UPLOAD,
                createdAt = System.currentTimeMillis(),
                lastUploadError = null,
            )
        )
    }

    suspend fun addItem(
        sessionId: String,
        materialId: String?,
        materialNameRaw: String,
        quantity: Double,
        unit: String,
        comment: String?,
        photoPaths: List<String>,
    ): MaterialOperationEntity? {
        val session = sessionDao.findById(sessionId) ?: return null
        if (!session.isEditable()) return null
        val now = System.currentTimeMillis()
        val item = MaterialOperationEntity(
            localId = UUID.randomUUID().toString(),
            serverId = null,
            type = OperationType.RECEIPT,
            siteId = session.siteId,
            materialId = materialId,
            materialNameRaw = materialNameRaw,
            quantity = quantity,
            unit = unit,
            userId = session.userId,
            deviceId = session.deviceId,
            vehicleNumber = session.vehicleNumber,
            driverName = null,
            comment = comment,
            createdAtLocal = now,
            receivedAtServer = null,
            syncStatus = SyncStatus.DRAFT,
            lastSyncError = null,
            idempotencyKey = UUID.randomUUID().toString(),
            sessionLocalId = session.localId,
        )
        operationDao.upsert(item)
        for (path in photoPaths) {
            attachmentDao.upsert(
                OperationAttachmentEntity(
                    localId = UUID.randomUUID().toString(),
                    operationLocalId = item.localId,
                    sessionLocalId = null,
                    localFilePath = path,
                    remoteUrl = null,
                    attachmentType = AttachmentType.CARGO,
                    uploadStatus = UploadStatus.PENDING_UPLOAD,
                    createdAt = now,
                    lastUploadError = null,
                )
            )
        }
        return item
    }

    suspend fun removeItem(itemId: String) {
        val op = operationDao.findById(itemId) ?: return
        if (op.syncStatus != SyncStatus.DRAFT) return
        operationDao.deleteById(itemId)
    }

    /** Сохраняет приёмку локально (можно потом доделать). */
    suspend fun saveLocally(sessionId: String): ReceiptSessionEntity? {
        val session = sessionDao.findById(sessionId) ?: return null
        if (!session.isEditable()) return session
        if (session.kind == SessionKind.RECEIPT) {
            val items = operationDao.findBySession(sessionId)
            if (items.isEmpty()) return session
        }
        val now = System.currentTimeMillis()
        val saved = session.copy(
            finalizedAt = now,
            syncStatus = SyncStatus.LOCAL_SAVED,
            lastSyncError = null,
        )
        sessionDao.upsert(saved)
        return saved
    }

    /** Окончательное завершение приёмки. */
    suspend fun complete(sessionId: String, confirmedByMol: Boolean): ReceiptSessionEntity? {
        val session = sessionDao.findById(sessionId) ?: return null
        if (!session.isEditable()) return session
        if (session.kind == SessionKind.RECEIPT) {
            val items = operationDao.findBySession(sessionId)
            if (items.isEmpty()) return session
        }
        val now = System.currentTimeMillis()
        val completed = session.copy(
            finalizedAt = session.finalizedAt ?: now,
            completedAt = now,
            confirmedByMol = confirmedByMol,
            syncStatus = SyncStatus.COMPLETED,
            lastSyncError = null,
        )
        sessionDao.upsert(completed)
        return completed
    }

    suspend fun cancelDraft(sessionId: String) {
        val session = sessionDao.findById(sessionId) ?: return
        if (!session.isEditable()) return
        val items = operationDao.findBySession(sessionId)
        for (item in items) {
            operationDao.deleteById(item.localId)
        }
        sessionDao.deleteById(sessionId)
    }

    private fun ReceiptSessionEntity.isEditable(): Boolean =
        syncStatus == SyncStatus.DRAFT || syncStatus == SyncStatus.LOCAL_SAVED
}
