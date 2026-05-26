package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная копия SourceDocumentDetail из контракта. На мобиле УПД только
 * читаем + привязываем к приёмке/отгрузке через sourceDocumentIds.
 */
@Entity(
    tableName = "remote_source_documents",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["supplierId"]),
        Index(value = ["status"]),
        Index(value = ["docDate"]),
    ],
)
data class RemoteSourceDocumentEntity(
    @PrimaryKey val id: String,
    val kind: String, // 'upd' | 'request'
    val direction: String, // 'inbound' | 'outbound'
    val status: String,
    val supplierId: String?,
    val recipientId: String?,
    val contractorId: String?,
    val recipientMolId: String?,
    val siteId: String?,
    val supplierName: String?,
    val contractorName: String?,
    val siteName: String?,
    val docNumber: String?,
    val docDate: String?,
    val totalSum: String?,
    val vatSum: String?,
    val expectedDate: String?,
    val origin: String,
    val parsedAt: String,
    val parseErrorCode: String?,
    val originalFilename: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "remote_source_document_items",
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceDocumentId"])],
)
data class RemoteSourceDocumentItemEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String,
    val materialId: String?,
    val nameRaw: String,
    val qty: String,
    val unit: String,
    val price: String?,
    val sum: String?,
    val vatRate: String?,
    val vatSum: String?,
    val expectedDate: String?,
    val lineNo: Int,
    val volumeM3: String?,
    val massKg: String?,
    val volumeConfidence: String?,
    val groupName: String?,
)

@Entity(
    tableName = "remote_source_document_attachments",
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceDocumentId"])],
)
data class RemoteSourceDocumentAttachmentEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String,
    val s3Key: String,
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val role: String, // 'original' | 'extracted_text'
)
