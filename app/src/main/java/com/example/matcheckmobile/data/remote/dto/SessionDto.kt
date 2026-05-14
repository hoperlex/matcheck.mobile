package com.example.matcheckmobile.data.remote.dto

data class SessionItemDto(
    val itemLocalId: String,
    val materialId: String?,
    val materialNameRaw: String,
    val quantity: Double,
    val unit: String,
    val comment: String?,
)

data class SessionDto(
    val idempotencyKey: String,
    val siteId: String,
    val supplierLocalId: String?,
    val vehicleNumber: String,
    val sourceDocumentLocalId: String?,
    val comment: String?,
    val userId: String,
    val deviceId: String,
    val startedAt: Long,
    val finalizedAt: Long,
    val items: List<SessionItemDto>,
)

data class SessionAcceptedDto(
    val serverId: String,
    val receivedAt: Long,
    val itemServerIds: Map<String, String>,
)
