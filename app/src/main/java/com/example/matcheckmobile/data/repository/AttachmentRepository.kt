package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow

class AttachmentRepository(
    private val attachmentDao: OperationAttachmentDao,
) {
    fun observePendingCount(): Flow<Int> =
        attachmentDao.observeCountByStatuses(PENDING_STATUSES)

    fun observePending(): Flow<List<OperationAttachmentEntity>> =
        attachmentDao.observeByStatuses(PENDING_STATUSES)

    companion object {
        val PENDING_STATUSES = listOf(
            UploadStatus.PENDING_UPLOAD,
            UploadStatus.UPLOADING,
            UploadStatus.UPLOAD_ERROR,
        )
    }
}
