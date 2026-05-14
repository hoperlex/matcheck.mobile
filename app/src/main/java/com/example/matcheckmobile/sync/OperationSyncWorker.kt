package com.example.matcheckmobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.local.dao.MaterialOperationDao
import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.SyncQueueEntity
import com.example.matcheckmobile.data.remote.ApiService
import com.example.matcheckmobile.data.remote.dto.AttachmentUploadRequest
import com.example.matcheckmobile.data.remote.dto.OperationDto
import com.example.matcheckmobile.domain.model.SyncStatus
import com.example.matcheckmobile.domain.model.UploadStatus

class OperationSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MatcheckApplication).container
        val api = container.apiService
        val operationDao = container.database.materialOperationDao()
        val attachmentDao = container.database.operationAttachmentDao()
        val syncQueueDao = container.database.syncQueueDao()

        val opsFailed = syncOperations(api, operationDao, syncQueueDao)
        val attsFailed = syncAttachments(api, operationDao, attachmentDao, syncQueueDao)

        return if (opsFailed || attsFailed) Result.retry() else Result.success()
    }

    private suspend fun syncOperations(
        api: ApiService,
        operationDao: MaterialOperationDao,
        syncQueueDao: SyncQueueDao,
    ): Boolean {
        var hadFailure = false
        val pending = operationDao.findBySyncStatuses(listOf(SyncStatus.PENDING, SyncStatus.ERROR))
        for (op in pending) {
            operationDao.updateSyncStatus(op.localId, SyncStatus.SYNCING, null)
            try {
                val sendResult = api.sendOperation(op.toDto())
                val accepted = sendResult.getOrThrow()
                operationDao.markSynced(
                    id = op.localId,
                    status = SyncStatus.SYNCED,
                    serverId = accepted.serverId,
                    receivedAtServer = accepted.receivedAt,
                )
                syncQueueDao.deleteByTarget(SyncQueueEntity.TARGET_OPERATION, op.localId)
            } catch (t: Throwable) {
                hadFailure = true
                operationDao.updateSyncStatus(op.localId, SyncStatus.ERROR, t.message)
            }
        }
        return hadFailure
    }

    private suspend fun syncAttachments(
        api: ApiService,
        operationDao: MaterialOperationDao,
        attachmentDao: OperationAttachmentDao,
        syncQueueDao: SyncQueueDao,
    ): Boolean {
        var hadFailure = false
        val pending = attachmentDao.findByStatuses(
            listOf(UploadStatus.PENDING_UPLOAD, UploadStatus.UPLOAD_ERROR)
        )
        for (attachment in pending) {
            val parent = operationDao.findById(attachment.operationLocalId)
            if (parent == null || parent.syncStatus != SyncStatus.SYNCED || parent.serverId == null) {
                continue
            }
            attachmentDao.updateUploadStatus(attachment.localId, UploadStatus.UPLOADING, null)
            try {
                val uploaded = api.uploadAttachment(
                    AttachmentUploadRequest(
                        operationServerId = parent.serverId,
                        attachmentLocalId = attachment.localId,
                        attachmentType = attachment.attachmentType.name,
                        localFilePath = attachment.localFilePath,
                    )
                ).getOrThrow()
                attachmentDao.markUploaded(
                    id = attachment.localId,
                    status = UploadStatus.UPLOADED,
                    remoteUrl = uploaded.remoteUrl,
                )
                syncQueueDao.deleteByTarget(SyncQueueEntity.TARGET_ATTACHMENT, attachment.localId)
            } catch (t: Throwable) {
                hadFailure = true
                attachmentDao.updateUploadStatus(
                    attachment.localId,
                    UploadStatus.UPLOAD_ERROR,
                    t.message,
                )
            }
        }
        return hadFailure
    }

    private fun MaterialOperationEntity.toDto(): OperationDto = OperationDto(
        idempotencyKey = idempotencyKey,
        type = type.name,
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
        createdAtLocal = createdAtLocal,
    )
}
