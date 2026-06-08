package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_shipments",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["pendingDeletionAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["statusCode"]),
        Index(value = ["kind"]),
    ],
)
data class RemoteShipmentEntity(
    @PrimaryKey val id: String,
    val statusCode: String,
    val statusLabel: String,
    val statusColor: String?,
    val kind: String, // 'contractor' | 'return' | 'transfer' | 'writeoff'
    // «Тип отгрузки» — семантическая категория отдельно от `kind`. С мобилы
    // приходит из dropdown'а на форме «Новая отгрузка»: «Вывоз материала»,
    // «Перемещение на объект», «Вывоз мусора», «Другое». NULL для отгрузок
    // с привязанной УПД и для legacy-записей. Сервер: миграция 0049.
    val purpose: String?,
    // Транзит — зеркало RemoteDeliveryEntity.inTransit. Default false.
    val inTransit: Boolean = false,
    val siteId: String,
    val receiverCounterpartyId: String?,
    val receiverMolId: String?,
    val destSiteId: String?,
    val vehiclePlate: String?,
    val driverName: String?,
    val shippedAt: String?,
    val inspectorId: String?,
    val comment: String?,
    val confirmedByMolUserId: String?,
    val confirmedByMolUserEmail: String?,
    val confirmedByMolAt: String?,
    val pendingDeletionAt: String?,
    val pendingDeletionByUserId: String?,
    val pendingDeletionByUserEmail: String?,
    val pendingDeletionReason: String?,
    val version: Int,
    val sourceDocumentIdsJson: String,
    val createdAt: String,
    val updatedAt: String,
    val conflictPending: Boolean = false,
    val serverSnapshotJson: String? = null,
    val lastSyncError: String? = null,
)

@Entity(
    tableName = "remote_shipment_items",
    foreignKeys = [
        ForeignKey(
            entity = RemoteShipmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["shipmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["shipmentId"]),
        Index(value = ["materialId"]),
    ],
)
data class RemoteShipmentItemEntity(
    @PrimaryKey val id: String,
    val shipmentId: String,
    val materialId: String?,
    val nameRaw: String,
    val qtyPlanned: String?,
    val qtyActual: String?,
    val unit: String,
    val comment: String?,
    val lineNo: Int,
    val volumeM3: String?,
    val massKg: String?,
    val price: String?,
    val vatRate: String?,
    val vatSum: String?,
    val volumeConfidence: String?,
    val groupName: String?,
)

@Entity(
    tableName = "remote_shipment_photos",
    foreignKeys = [
        ForeignKey(
            entity = RemoteShipmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["shipmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["shipmentId"]),
        Index(value = ["contentHash"]),
        Index(value = ["uploadStatus"]),
    ],
)
data class RemoteShipmentPhotoEntity(
    @PrimaryKey val id: String,
    val shipmentId: String,
    val kind: String,
    // 'before' / 'after'. На сервере default 'before' (см. миграцию 0048),
    // в БД мобилы новая колонка добавлена миграцией Room с тем же default.
    val stage: String,
    val s3Key: String?,
    val thumbS3Key: String?,
    val contentHash: String?,
    val takenAt: String,
    val uploadedAt: String?,
    val idempotencyKey: String,
    val contentType: String,
    val localBlobPath: String?,
    val localThumbPath: String?,
    val uploadStatus: String,
    val lastUploadError: String?,
)
