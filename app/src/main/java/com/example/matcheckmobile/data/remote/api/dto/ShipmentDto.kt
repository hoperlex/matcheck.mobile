package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * Зеркалит контракт shipments.ts. Зеркально Delivery, но с обязательным
 * [kind] (contractor | return | transfer | writeoff) и условными полями
 * receiverCounterpartyId/destSiteId — см. MOBILE_API.md «Shipments».
 */
@Serializable
data class ShipmentDto(
    val id: String,
    val status: StatusDto,
    val kind: String, // 'contractor' | 'return' | 'transfer' | 'writeoff'
    val siteId: String,
    val receiverCounterpartyId: String? = null,
    val destSiteId: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val shippedAt: String? = null,
    val inspectorId: String? = null,
    val comment: String? = null,
    val confirmedByMolUserId: String? = null,
    val confirmedByMolUserEmail: String? = null,
    val confirmedByMolAt: String? = null,
    val pendingDeletionAt: String? = null,
    val pendingDeletionByUserId: String? = null,
    val pendingDeletionByUserEmail: String? = null,
    val pendingDeletionReason: String? = null,
    val version: Int,
    val sourceDocumentIds: List<String> = emptyList(),
    val items: List<ShipmentItemDto> = emptyList(),
    val photos: List<ShipmentPhotoDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ShipmentItemDto(
    val id: String,
    val materialId: String? = null,
    val nameRaw: String,
    val qtyPlanned: String? = null,
    val qtyActual: String? = null,
    val unit: String,
    val comment: String? = null,
    val lineNo: Int,
    val volumeM3: String? = null,
    val massKg: String? = null,
    val volumeConfidence: String? = null,
    val groupName: String? = null,
)

@Serializable
data class ShipmentPhotoDto(
    val id: String,
    val kind: String,
    val s3Key: String,
    val thumbS3Key: String? = null,
    val contentHash: String? = null,
    val takenAt: String,
    val uploadedAt: String? = null,
)

@Serializable
data class ShipmentUpsertRequest(
    val id: String? = null,
    val statusCode: String, // 'not_filled' | 'draft' | 'shipped' | 'confirmed_mol'
    val kind: String,
    val siteId: String,
    val receiverCounterpartyId: String? = null,
    val destSiteId: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val shippedAt: String? = null,
    val comment: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    val items: List<ShipmentUpsertItem> = emptyList(),
    val baseVersion: Int? = null,
)

@Serializable
data class ShipmentUpsertItem(
    val id: String? = null,
    val materialId: String? = null,
    val nameRaw: String,
    val qtyPlanned: String? = null,
    val qtyActual: String? = null,
    val unit: String = "шт",
    val comment: String? = null,
    val lineNo: Int,
    val volumeM3: String? = null,
    val massKg: String? = null,
    val volumeConfidence: String? = null,
    val groupName: String? = null,
)

@Serializable
data class ShipmentListResponse(
    val items: List<ShipmentDto>,
    val total: Int,
)

@Serializable
data class ShipmentConflictResponse(
    val error: String,
    val serverVersion: Int,
    val server: ShipmentDto,
)
