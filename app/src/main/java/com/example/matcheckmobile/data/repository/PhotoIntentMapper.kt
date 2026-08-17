package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.domain.model.PhotoIntent
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import java.util.UUID

/**
 * Идентификатор строки фото выводится из (parentId, sourcePath), а не
 * генерируется случайно.
 *
 * Зачем: повторная финализация обязана попасть в ТУ ЖЕ строку. Процесс может
 * умереть после успешной транзакции, но до удаления черновика — тогда
 * инспектор нажмёт «Завершить» ещё раз, и случайный id завёл бы второй
 * комплект фото (а на сервере — второй presign того же кадра).
 *
 * Считается в репозитории, а не в форме: итоговый parentId известен только
 * после natural-key dedup внутри upsert.
 */
internal fun photoIntentId(parentId: String, sourcePath: String): String =
    UUID.nameUUIDFromBytes("$parentId|$sourcePath".toByteArray(Charsets.UTF_8)).toString()

/**
 * PhotoIntent → durable-строка в состоянии PENDING_PREPARE.
 *
 * Заполнено только то, что известно до подготовки: contentHash, s3Key и
 * localBlobPath появятся после PhotoPrepareWorker. contentType — image/jpeg
 * сразу: подготовка всегда выдаёт JPEG, а поле NOT NULL.
 */
internal fun PhotoIntent.toDeliveryPhotoEntity(deliveryId: String): RemoteDeliveryPhotoEntity {
    val id = photoIntentId(deliveryId, sourcePath)
    return RemoteDeliveryPhotoEntity(
        id = id,
        deliveryId = deliveryId,
        kind = kind,
        stage = stage,
        s3Key = null,
        thumbS3Key = null,
        contentHash = null,
        takenAt = takenAt,
        uploadedAt = null,
        // Ключ идемпотентности сервера тоже обязан переживать повтор:
        // при случайном значении второй presign того же кадра создал бы
        // на сервере вторую строку.
        idempotencyKey = id,
        contentType = JPEG_MIME,
        localBlobPath = null,
        localThumbPath = null,
        uploadStatus = RemotePhotoStatus.PENDING_PREPARE,
        lastUploadError = null,
        sourcePath = sourcePath,
        preparingSince = null,
    )
}

internal fun PhotoIntent.toShipmentPhotoEntity(shipmentId: String): RemoteShipmentPhotoEntity {
    val id = photoIntentId(shipmentId, sourcePath)
    return RemoteShipmentPhotoEntity(
        id = id,
        shipmentId = shipmentId,
        kind = kind,
        stage = stage,
        s3Key = null,
        thumbS3Key = null,
        contentHash = null,
        takenAt = takenAt,
        uploadedAt = null,
        idempotencyKey = id,
        contentType = JPEG_MIME,
        localBlobPath = null,
        localThumbPath = null,
        uploadStatus = RemotePhotoStatus.PENDING_PREPARE,
        lastUploadError = null,
        sourcePath = sourcePath,
        preparingSince = null,
    )
}

private const val JPEG_MIME = "image/jpeg"
