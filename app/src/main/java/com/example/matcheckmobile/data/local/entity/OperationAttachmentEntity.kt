package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.UploadStatus

@Entity(
    tableName = "operation_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MaterialOperationEntity::class,
            parentColumns = ["localId"],
            childColumns = ["operationLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReceiptSessionEntity::class,
            parentColumns = ["localId"],
            childColumns = ["sessionLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationLocalId"]),
        Index(value = ["sessionLocalId"]),
        Index(value = ["uploadStatus"]),
    ],
)
data class OperationAttachmentEntity(
    @PrimaryKey val localId: String,
    val operationLocalId: String?,
    val sessionLocalId: String?,
    val localFilePath: String,
    val remoteUrl: String?,
    val attachmentType: AttachmentType,
    val uploadStatus: UploadStatus,
    val createdAt: Long,
    val lastUploadError: String?,
)
