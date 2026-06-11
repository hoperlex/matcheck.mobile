package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная копия серверной приёмки (Delivery). Поля 1:1 с DeliveryDto +
 * локальная служебка для sync (conflictPending, lastSyncError).
 *
 * id = UUID, generates как на клиенте (для черновиков), так и на сервере.
 * status хранится "разложенным": [statusCode] (код для отправки в upsert) и
 * вспомогательные [statusLabel]/[statusColor] для UI без join'ов.
 */
@Entity(
    tableName = "remote_deliveries",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["pendingDeletionAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["statusCode"]),
    ],
)
data class RemoteDeliveryEntity(
    @PrimaryKey val id: String,
    val statusCode: String,
    val statusLabel: String,
    val statusColor: String?,
    val siteId: String,
    val supplierId: String?,
    val contractorId: String?,
    val recipientMolId: String?,
    val vehiclePlate: String?,
    val driverName: String?,
    val arrivedAt: String?,
    val inspectorId: String?,
    val comment: String?,
    // Транзит — флаг «машина после нашей операции едет с другим грузом».
    // Чекбокс на 1 этапе мобилы. Default false. См. миграцию 0051 + Room 20→21.
    val inTransit: Boolean = false,
    // ОС — флаг «накладная на движение основных средств». Чекбокс на
    // 1 этапе мобилы рядом с Транзитом. Default false (legacy-записи
    // не страдают). См. серверную миграцию 0065 + Room 21→22.
    val isAssets: Boolean = false,
    val confirmedByMolUserId: String?,
    val confirmedByMolUserEmail: String?,
    val confirmedByMolAt: String?,
    val pendingDeletionAt: String?,
    val pendingDeletionByUserId: String?,
    val pendingDeletionByUserEmail: String?,
    val pendingDeletionReason: String?,
    val version: Int,
    /** JSON-массив id'шек привязанных УПД (List<String>). */
    val sourceDocumentIdsJson: String,
    val createdAt: String,
    val updatedAt: String,
    /** true — на upsert вернулся 409 conflict, ждём UI разрешения. */
    val conflictPending: Boolean = false,
    /** Snapshot DeliveryDto на сервере на момент 409 — для diff в UI разрешения. */
    val serverSnapshotJson: String? = null,
    val lastSyncError: String? = null,
)

@Entity(
    tableName = "remote_delivery_items",
    foreignKeys = [
        ForeignKey(
            entity = RemoteDeliveryEntity::class,
            parentColumns = ["id"],
            childColumns = ["deliveryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["deliveryId"]),
        Index(value = ["materialId"]),
    ],
)
data class RemoteDeliveryItemEntity(
    @PrimaryKey val id: String,
    val deliveryId: String,
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
    tableName = "remote_delivery_photos",
    foreignKeys = [
        ForeignKey(
            entity = RemoteDeliveryEntity::class,
            parentColumns = ["id"],
            childColumns = ["deliveryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["deliveryId"]),
        Index(value = ["contentHash"]),
        Index(value = ["uploadStatus"]),
    ],
)
data class RemoteDeliveryPhotoEntity(
    @PrimaryKey val id: String,
    val deliveryId: String,
    val kind: String, // 'document' | 'cargo' | 'vehicle' | 'other'
    /**
     * Этап приёмки: 'before' — 1-й этап (КПП/осмотр), 'after' — 2-й этап
     * (после выгрузки и подтверждения МОЛ). Default 'before' для legacy
     * фото; на сервере поле тоже NOT NULL DEFAULT 'before'.
     */
    val stage: String = "before",
    /** Известен после успешного presign / pull. До этого null. */
    val s3Key: String?,
    val thumbS3Key: String?,
    val contentHash: String?,
    val takenAt: String,
    /** null → orphan / ещё не загружен. */
    val uploadedAt: String?,
    /** Уникальный ключ идемпотентности — генерится 1 раз при capture. */
    val idempotencyKey: String,
    /** Реальный MIME ("image/jpeg", "image/heic" и т.д.) для presign. */
    val contentType: String,
    /** Абсолютный путь к main blob в filesDir. null после удаления локальной копии. */
    val localBlobPath: String?,
    val localThumbPath: String?,
    val uploadStatus: String, // PENDING_UPLOAD | UPLOADING | UPLOADED | UPLOAD_ERROR
    val lastUploadError: String?,
)
