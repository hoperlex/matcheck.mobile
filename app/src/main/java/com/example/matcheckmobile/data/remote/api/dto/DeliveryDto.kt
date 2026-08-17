package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * Зеркалит контракт [matcheck-main/packages/contracts/src/deliveries.ts].
 * Numeric-поля (qty, sum, mass и т.п.) — String, потому что на сервере это
 * Decimal, и потеря точности при разборе через Double неприемлема.
 */
@Serializable
data class DeliveryDto(
    val id: String,
    val status: StatusDto,
    val siteId: String,
    val supplierId: String? = null,
    val contractorId: String? = null,
    // Получатель — Подрядчик/МОЛ; сервер берёт его из УПД при привязке.
    val recipientMolId: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val arrivedAt: String? = null,
    val inspectorId: String? = null,
    val comment: String? = null,
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
    val confirmedByMolUserId: String? = null,
    val confirmedByMolUserEmail: String? = null,
    val confirmedByMolAt: String? = null,
    val pendingDeletionAt: String? = null,
    val pendingDeletionByUserId: String? = null,
    val pendingDeletionByUserEmail: String? = null,
    val pendingDeletionReason: String? = null,
    val version: Int,
    val sourceDocumentIds: List<String> = emptyList(),
    val items: List<DeliveryItemDto> = emptyList(),
    val photos: List<DeliveryPhotoDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DeliveryItemDto(
    val id: String,
    val materialId: String? = null,
    /**
     * Происхождение позиции: документ и его строка. Заполнено, когда приёмка
     * собрана по УПД; null у строк, добавленных инспектором руками. Веб-портал
     * раскладывает по этим полям позиции приёмки на секции по документам — что
     * и делает осмысленной приёмку сразу по нескольким УПД одной машины.
     */
    val sourceDocumentId: String? = null,
    val sourceDocumentItemId: String? = null,
    val nameRaw: String,
    val qtyPlanned: String? = null,
    val qtyActual: String? = null,
    val unit: String,
    val comment: String? = null,
    val lineNo: Int,
    val volumeM3: String? = null,
    val massKg: String? = null,
    // Финансы из УПД: цена за единицу, ставка НДС, сумма НДС по позиции.
    // Все Decimal-как-String, см. комментарий к DeliveryDto.
    val price: String? = null,
    val vatRate: String? = null,
    val vatSum: String? = null,
    val volumeConfidence: String? = null,
    val groupName: String? = null,
)

@Serializable
data class DeliveryPhotoDto(
    val id: String,
    val kind: String, // 'document' | 'cargo' | 'vehicle' | 'other'
    /**
     * 'before' (1 Этап) / 'after' (2 Этап). На сервере NOT NULL DEFAULT 'before',
     * поэтому делаем поле опциональным с тем же дефолтом для совместимости
     * с записями, созданными до миграции 0037.
     */
    val stage: String = "before",
    val s3Key: String,
    val thumbS3Key: String? = null,
    val contentHash: String? = null,
    val takenAt: String,
    /** null = orphan-запись, PUT в S3 не подтверждён. См. MOBILE_API.md photos pipeline. */
    val uploadedAt: String? = null,
)

/** Запрос upsert приёмки (POST /api/v1/deliveries). */
@Serializable
data class DeliveryUpsertRequest(
    val id: String? = null,
    val statusCode: String, // 'not_filled' | 'draft' | 'filled' | 'confirmed_mol'
    val siteId: String,
    val supplierId: String? = null,
    val contractorId: String? = null,
    val recipientMolId: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val arrivedAt: String? = null,
    /**
     * Момент, когда инспектор фактически подтвердил приёмку на планшете —
     * «Завершить 2 Этап» или ручной внос. Раньше это время ставил сервер в
     * секунду приёма мутации: при офлайне все накопленные подтверждения
     * получали одно и то же время (04.08, ЖК АЛИЯ — четыре приёмки с 05:23).
     * Сервер сверяет присланное значение с arrivedAt и своими часами.
     */
    val confirmedByMolAt: String? = null,
    val comment: String? = null,
    /** Транзит — см. DeliveryDto.inTransit. Default false. */
    val inTransit: Boolean = false,
    /** ОС — см. DeliveryDto.isAssets. Default false. */
    val isAssets: Boolean = false,
    val sourceDocumentIds: List<String> = emptyList(),
    val items: List<DeliveryUpsertItem> = emptyList(),
    val baseVersion: Int? = null,
)

@Serializable
data class DeliveryUpsertItem(
    val id: String? = null,
    val materialId: String? = null,
    /** Происхождение позиции, см. [DeliveryItemDto.sourceDocumentId]. */
    val sourceDocumentId: String? = null,
    val sourceDocumentItemId: String? = null,
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
data class DeliveryListResponse(
    val items: List<DeliveryDto>,
    val total: Int,
)

@Serializable
data class DeliveryConflictResponse(
    val error: String,
    val serverVersion: Int,
    val server: DeliveryDto,
)

@Serializable
data class MarkDeletionRequest(
    val reason: String? = null,
)
