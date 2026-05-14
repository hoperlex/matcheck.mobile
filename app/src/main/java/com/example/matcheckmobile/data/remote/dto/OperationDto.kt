package com.example.matcheckmobile.data.remote.dto

data class OperationDto(
    val idempotencyKey: String,
    val type: String,
    val siteId: String,
    val materialId: String?,
    val materialNameRaw: String,
    val quantity: Double,
    val unit: String,
    val userId: String,
    val deviceId: String,
    val vehicleNumber: String?,
    val driverName: String?,
    val comment: String?,
    val createdAtLocal: Long,
)

data class OperationAcceptedDto(
    val serverId: String,
    val receivedAt: Long,
)
