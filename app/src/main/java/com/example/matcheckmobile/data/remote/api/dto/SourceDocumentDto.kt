package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * Зеркалит SourceDocumentDetailSchema из source-documents.ts (та же модель,
 * что отдаёт /sync — там приходит детальный DTO).
 *
 * Поля `validation`, `parseErrorDetails` сильно специфичны для web-UI и
 * редко нужны на мобиле — оставляем `JsonElement?` через String, чтобы
 * не плодить вложенных схем. При необходимости подцепим явные типы.
 */
@Serializable
data class SourceDocumentDto(
    val id: String,
    val kind: String, // 'upd' | 'request'
    val direction: String, // 'inbound' | 'outbound'
    val status: String, // 'parsed' | 'parse_failed' | ...
    val supplierId: String? = null,
    val recipientId: String? = null,
    val contractorId: String? = null,
    // Получатель-МОЛ (отдельно от recipientId — это разные поля на сервере).
    // У приёмки и УПД альтернативы: либо contractor_id, либо recipient_mol_id.
    val recipientMolId: String? = null,
    val siteId: String? = null,
    val supplierName: String? = null,
    val contractorName: String? = null,
    val siteName: String? = null,
    val docNumber: String? = null,
    val docDate: String? = null,
    val totalSum: String? = null,
    val vatSum: String? = null,
    val expectedDate: String? = null,
    val origin: String, // 'edo_diadoc' | ...
    val llmProviderId: String? = null,
    val llmConfidence: String? = null,
    val parsedAt: String,
    val queuedAt: String? = null,
    val processedAt: String? = null,
    val parseErrorCode: String? = null,
    val originalFilename: String? = null,
    val contentHash: String? = null,
    val jobAttempts: Int = 0,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val items: List<SourceItemDto> = emptyList(),
    val attachments: List<SourceAttachmentDto> = emptyList(),
)

@Serializable
data class SourceItemDto(
    val id: String,
    val materialId: String? = null,
    val nameRaw: String,
    val qty: String,
    val unit: String,
    val price: String? = null,
    val sum: String? = null,
    val vatRate: String? = null,
    val vatSum: String? = null,
    val expectedDate: String? = null,
    val lineNo: Int,
    val volumeM3: String? = null,
    val massKg: String? = null,
    val volumeConfidence: String? = null,
    val groupName: String? = null,
)

@Serializable
data class SourceAttachmentDto(
    val id: String,
    val s3Key: String,
    val filename: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val role: String, // 'original' | 'extracted_text'
)

@Serializable
data class SourceDocumentFileResponse(
    val url: String,
    val filename: String,
    val mimeType: String? = null,
)
