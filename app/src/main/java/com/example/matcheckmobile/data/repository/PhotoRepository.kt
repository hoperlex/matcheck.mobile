package com.example.matcheckmobile.data.repository

import android.net.Uri
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.media.RemotePhotoStorage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-first capture: пишет фото в Room + локальный blob, ставит
 * uploadStatus=PENDING_UPLOAD. Загрузку на сервер (presign/PUT/confirm)
 * делает [PhotoUploadProcessor] асинхронно — после того, как parent
 * (delivery/shipment) уйдёт на сервер.
 */
class PhotoRepository(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val photoStorage: RemotePhotoStorage,
) {

    /**
     * Подготовка кадра (decode + resize + два JPEG-энкода) — на IO, а не на
     * вызывающем потоке. Формы зовут capture* из `viewModelScope.launch`, то
     * есть с Main: раньше на UI-потоке молотился декод ≤2048 px, теперь — до
     * 4095 px (после снятия лишнего деления вдвое), и на пачке фото при
     * «Завершить Этап» это уже кандидат в ANR на слабом планшете.
     */
    private suspend fun prepare(sourceUri: Uri, photoId: String) =
        withContext(Dispatchers.IO) { photoStorage.prepareFromUri(sourceUri, photoId) }

    /**
     * @param kind 'cargo' | 'vehicle' | 'document' | 'other'.
     *   Для бумажного УПД на КПП — 'document'.
     * @param stage 'before' (1-й Этап, КПП) / 'after' (2-й Этап, после
     *   подтверждения МОЛ). Default 'before' для совместимости.
     * @return локальный photoId (UUID v4). Этот же id используется на сервере
     *   после presign.
     */
    suspend fun captureForDelivery(
        deliveryId: String,
        kind: String,
        sourceUri: Uri,
        stage: String = "before",
        /**
         * Момент съёмки по часам планшета (ISO-8601). Считается ОДИН раз —
         * во вьюмодели, где ещё есть путь к файлу ([photoTakenAtIso]) — и
         * дальше живёт только в Room: при отправке presign берёт сохранённое
         * значение и файл не перечитывает. Иначе при долгом офлайне,
         * перезапуске или перезаписи исходника время «поплыло» бы.
         */
        takenAt: String,
    ): String {
        val photoId = UUID.randomUUID().toString()
        val prepared = prepare(sourceUri, photoId)
        deliveryDao.upsertPhoto(
            RemoteDeliveryPhotoEntity(
                id = photoId,
                deliveryId = deliveryId,
                kind = kind,
                stage = stage,
                s3Key = null,
                thumbS3Key = null,
                contentHash = prepared.mainSha256Hex,
                takenAt = takenAt,
                uploadedAt = null,
                idempotencyKey = UUID.randomUUID().toString(),
                contentType = prepared.contentType,
                localBlobPath = prepared.mainFile.absolutePath,
                localThumbPath = prepared.thumbFile?.absolutePath,
                uploadStatus = "PENDING_UPLOAD",
                lastUploadError = null,
            ),
        )
        return photoId
    }

    suspend fun captureForShipment(
        shipmentId: String,
        kind: String,
        sourceUri: Uri,
        stage: String = "before",
        /** См. [captureForDelivery.takenAt]. */
        takenAt: String,
    ): String {
        val photoId = UUID.randomUUID().toString()
        val prepared = prepare(sourceUri, photoId)
        shipmentDao.upsertPhoto(
            RemoteShipmentPhotoEntity(
                id = photoId,
                shipmentId = shipmentId,
                kind = kind,
                stage = stage,
                s3Key = null,
                thumbS3Key = null,
                contentHash = prepared.mainSha256Hex,
                takenAt = takenAt,
                uploadedAt = null,
                idempotencyKey = UUID.randomUUID().toString(),
                contentType = prepared.contentType,
                localBlobPath = prepared.mainFile.absolutePath,
                localThumbPath = prepared.thumbFile?.absolutePath,
                uploadStatus = "PENDING_UPLOAD",
                lastUploadError = null,
            ),
        )
        return photoId
    }
}
