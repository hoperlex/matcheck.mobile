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
    /** Получатель-МОЛ (физлицо). XOR с receiverCounterpartyId на стороне сервера. */
    val receiverMolId: String? = null,
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
    /**
     * «Тип отгрузки» — серверное поле, заполняется инспектором на форме
     * «Новая отгрузка» (empty-draft). Значения: «Вывоз материала»,
     * «Перемещение на объект», «Вывоз мусора», «Другое». NULL для
     * отгрузок с УПД и legacy-записей. Default `null` для совместимости
     * со старым сервером без миграции 0049.
     */
    val purpose: String? = null,
    /**
     * Транзит — флаг «машина является частью транзитного рейса».
     * Default false для совместимости со старым сервером (миграция 0051).
     */
    val inTransit: Boolean = false,
    /**
     * ОС — флаг «накладная на движение основных средств». Default false
     * для совместимости со старым сервером (миграция 0065). Веб-портал
     * показывает бейдж рядом с «Транзит».
     */
    val isAssets: Boolean = false,
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
    // Финансовый снимок позиции из УПД — зеркалит DeliveryItemDto.
    val price: String? = null,
    val vatRate: String? = null,
    val vatSum: String? = null,
    val volumeConfidence: String? = null,
    val groupName: String? = null,
)

@Serializable
data class ShipmentPhotoDto(
    val id: String,
    val kind: String,
    // 'before' / 'after' — этап у фото отгрузки. Default 'before' для
    // совместимости со старым сервером (миграция 0048): если поле не
    // пришло — относим к 1-му этапу. См. зеркало DeliveryPhotoDto.stage.
    val stage: String = "before",
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
    val receiverMolId: String? = null,
    val destSiteId: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val shippedAt: String? = null,
    val comment: String? = null,
    /** «Тип отгрузки» — см. ShipmentDto.purpose. */
    val purpose: String? = null,
    /** Транзит — см. ShipmentDto.inTransit. Default false. */
    val inTransit: Boolean = false,
    /** ОС — см. ShipmentDto.isAssets. Default false. */
    val isAssets: Boolean = false,
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
    val price: String? = null,
    val vatRate: String? = null,
    val vatSum: String? = null,
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
