package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * Запрос presign для двухэтапной загрузки фото. См. MOBILE_API.md «Photo pipeline».
 * Поле deliveryId оставлено для legacy-совместимости (≤ Phase 1 сервера) — на новых
 * вызовах используется пара operationKind/operationId.
 */
@Serializable
data class PhotoPresignRequest(
    val operationKind: String, // 'delivery' | 'shipment'
    val operationId: String,
    val kind: String, // 'document' | 'cargo' | 'vehicle' | 'other'
    val contentHash: String,
    val idempotencyKey: String,
    val contentType: String,
    val thumbContentHash: String? = null,
    /**
     * Этап приёмки для фото delivery: 'before' (1-й этап) / 'after' (2-й этап).
     * Сервер игнорирует на shipment. Если поле не передано — сервер пишет 'before'.
     */
    val stage: String? = null,
    val deliveryId: String? = null,
)

@Serializable
data class PhotoPresignResponse(
    val photoId: String,
    val s3Key: String,
    val thumbS3Key: String? = null,
    /** Если true — клиент не делает PUT в S3, фото уже было загружено. */
    val alreadyExists: Boolean,
    val uploadUrl: String? = null,
    val thumbUploadUrl: String? = null,
)

@Serializable
data class PhotoUrlResponse(
    val url: String,
    val expiresIn: Long,
)

@Serializable
data class PhotoConfirmResponse(
    val uploadedAt: String,
)
