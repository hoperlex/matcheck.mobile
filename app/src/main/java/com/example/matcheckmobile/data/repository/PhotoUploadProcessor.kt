package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.remote.api.PhotosApi
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignRequest
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignResponse
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.File

/**
 * Двухэтапная загрузка фото на сервер. Алгоритм по MOBILE_API.md «Photo
 * pipeline»:
 *
 * 1. POST /photos/presign с operationKind/operationId, contentHash,
 *    idempotencyKey, contentType
 * 2. Если alreadyExists=true → PUT в S3 пропускается
 * 3. Иначе PUT в S3 (presigned URL, без Authorization)
 * 4. POST /photos/{id}/confirm → сервер делает S3.HEAD + uploaded_at=now
 *
 * Гард на запуск: parent (delivery/shipment) должен быть на сервере
 * (version > 0 и нет pending upsert-мутации). Иначе presign даст 404.
 *
 * Для PUT в S3 используется отдельный OkHttpClient без mobile-interceptor-ов
 * (presigned URL уже подписан AWS-методом).
 */
class PhotoUploadProcessor(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
    private val photosApi: PhotosApi,
    /**
     * 403 `foreign_site` на presign/confirm — снимок относится к записи чужого
     * объекта. Ретраить бесполезно (сервер будет отказывать всегда), удалять
     * нельзя (потеря данных) → терминальный карантин.
     */
    private val quarantine: ForeignSiteQuarantine,
    /**
     * Транспорт PUT в S3. Вынесен в параметр ради тестов: они подставляют
     * клиент с короткими таймаутами, иначе проверка `callTimeout` заняла бы три
     * минуты реального времени.
     */
    private val s3Uploader: S3Uploader = S3Uploader(),
) {

    /**
     * Чистит фото с локально потерянными blob'ами (status=UPLOAD_ERROR +
     * lastUploadError="blob missing"). Это «зомби» от старых попыток до
     * фикса recycled-bitmap — восстановить нечем, держать в БД смысла нет.
     */
    suspend fun cleanupBrokenLocalBlobs(): Int {
        val a = deliveryDao.deletePhotosByStatusAndError("UPLOAD_ERROR", "blob missing")
        val b = shipmentDao.deletePhotosByStatusAndError("UPLOAD_ERROR", "blob missing")
        return a + b
    }

    suspend fun processAll(): PhotoUploadResult {
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var quarantined = 0

        // Сначала «оживляем» фото, застрявшие в UPLOADING после убийства
        // процесса mid-upload — иначе findPhotosByStatus(PENDING/ERROR) их не
        // подхватит и они навсегда останутся orphan'ами. Делаем в начале цикла:
        // активной заливки сейчас нет, поэтому любое UPLOADING = недогруженный
        // остаток. Повторный presign идемпотентен (дедуп по contentHash), так
        // что даже при гонке двойной заливки не будет дубля на сервере.
        deliveryDao.resetStuckUploadingPhotos()
        shipmentDao.resetStuckUploadingPhotos()

        // Карантинные (QUARANTINED_FOREIGN_SITE) сюда НЕ попадают — иначе
        // каждый синк крутил бы вечный цикл 403 на чужих записях.
        val deliveryPhotos = deliveryDao.findPhotosByStatus(RemotePhotoStatus.UPLOADABLE)
        for (photo in deliveryPhotos) {
            when (uploadDeliveryPhoto(photo)) {
                UploadOutcome.Uploaded -> uploaded++
                UploadOutcome.Skipped -> skipped++
                UploadOutcome.Failed -> failed++
                UploadOutcome.Quarantined -> quarantined++
            }
        }

        val shipmentPhotos = shipmentDao.findPhotosByStatus(RemotePhotoStatus.UPLOADABLE)
        for (photo in shipmentPhotos) {
            when (uploadShipmentPhoto(photo)) {
                UploadOutcome.Uploaded -> uploaded++
                UploadOutcome.Skipped -> skipped++
                UploadOutcome.Failed -> failed++
                UploadOutcome.Quarantined -> quarantined++
            }
        }

        return PhotoUploadResult(
            uploaded = uploaded,
            skipped = skipped,
            failed = failed,
            quarantined = quarantined,
        )
    }

    /**
     * 403 с телом `{"error":"foreign_site"}`. Именно тело, а не голый код:
     * 403 `forbidden` (чужой автор в том же объекте) карантинить нельзя —
     * это транзиентная ситуация, а не «остаток прошлого аккаунта».
     */
    private fun isForeignSite(error: Throwable): Boolean {
        if (error !is HttpException || error.code() != 403) return false
        val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        return classifyMutationFailure(error.code(), raw) == MutationFailure.ForeignSite
    }

    private suspend fun uploadDeliveryPhoto(photo: RemoteDeliveryPhotoEntity): UploadOutcome {
        val parent = deliveryDao.findById(photo.deliveryId) ?: return UploadOutcome.Skipped
        if (!isParentReady(parentType = "delivery", parentId = photo.deliveryId, parentVersion = parent.version)) {
            return UploadOutcome.Skipped
        }
        val blob = photo.localBlobPath?.let(::File)?.takeIf { it.exists() }
            ?: run {
                // Локальный blob потерян (например, после ручной очистки кэша).
                // Если запись уже uploaded на сервере — оставляем как есть,
                // иначе делаем drop, чтобы не крутить вечно.
                deliveryDao.upsertPhoto(photo.copy(uploadStatus = "UPLOAD_ERROR", lastUploadError = "blob missing"))
                return UploadOutcome.Failed
            }

        deliveryDao.upsertPhoto(photo.copy(uploadStatus = "UPLOADING", lastUploadError = null))

        return runCatching {
            val presign = photosApi.presign(buildPresignRequest("delivery", photo.deliveryId, photo, blob))
            doUpload(blob = blob, thumb = photo.localThumbPath?.let(::File), presign = presign, contentType = photo.contentType)
            // Сервер генерирует photoId сам (см. apps/api/.../photos.ts:167) — confirm
            // и последующие /sync ожидают именно его. Используем мобильный id только до
            // первого успешного presign, дальше — серверный, иначе confirm уйдёт в 404
            // not_found и запись на сервере останется orphan (uploadedAt=null), а через
            // час будет вычищена cleanup-job'ом.
            confirmIfNeeded(presign.photoId, presign)
            // После успешного UPLOADED локальный blob и thumb больше не нужны:
            // в UI клиента фото показывается через PhotoFetcher с S3 (presigned
            // URL), retry уже не пойдёт. Удаляем файлы и зануляем пути в Room,
            // чтобы за активной работой не накапливались гигабайты «мёртвых»
            // jpeg в app-private external storage.
            val updated = photo.copy(
                id = presign.photoId,
                s3Key = presign.s3Key,
                thumbS3Key = presign.thumbS3Key,
                uploadStatus = "UPLOADED",
                uploadedAt = java.time.Instant.now().toString(),
                lastUploadError = null,
                localBlobPath = null,
                localThumbPath = null,
            )
            if (presign.photoId != photo.id) {
                // Меняем PK — Room не умеет это через upsert, удаляем старую и вставляем новую.
                deliveryDao.deletePhotoById(photo.id)
            }
            deliveryDao.upsertPhoto(updated)
            deleteLocalBlobsQuietly(photo.localBlobPath, photo.localThumbPath)
            UploadOutcome.Uploaded
        }.getOrElse { error ->
            // Отмена — не ошибка загрузки. Если пометить её как UPLOAD_ERROR,
            // снятие воркера выглядело бы как сбой S3, а распространение отмены
            // сломалось бы. Фото остаётся в UPLOADING и оживёт на следующем
            // цикле через resetStuckUploadingPhotos().
            if (error is CancellationException) throw error
            if (isForeignSite(error)) {
                // Снимок принадлежит записи чужого объекта: ретрай бесполезен.
                // Файл и строку сохраняем, статус — терминальный карантин.
                quarantine.quarantineDeliveryPhoto(photo)
                return UploadOutcome.Quarantined
            }
            // «Фото не долетело»: presign/PUT(S3)/confirm упали. PUT идёт по
            // rawS3Client (без Sentry-интерсептора), поэтому сбои Cloud.ru видны
            // только тут. Шлём исключение с тегами (без подписи URL/тела).
            Sentry.withScope { scope ->
                scope.setTag("phase", "photo_upload")
                scope.setTag("parent", "delivery")
                Sentry.captureException(error)
            }
            deliveryDao.upsertPhoto(
                photo.copy(
                    uploadStatus = RemotePhotoStatus.UPLOAD_ERROR,
                    lastUploadError = error.message ?: "upload failed",
                ),
            )
            UploadOutcome.Failed
        }
    }

    private suspend fun uploadShipmentPhoto(photo: RemoteShipmentPhotoEntity): UploadOutcome {
        val parent = shipmentDao.findById(photo.shipmentId) ?: return UploadOutcome.Skipped
        if (!isParentReady(parentType = "shipment", parentId = photo.shipmentId, parentVersion = parent.version)) {
            return UploadOutcome.Skipped
        }
        val blob = photo.localBlobPath?.let(::File)?.takeIf { it.exists() }
            ?: run {
                shipmentDao.upsertPhoto(photo.copy(uploadStatus = "UPLOAD_ERROR", lastUploadError = "blob missing"))
                return UploadOutcome.Failed
            }

        shipmentDao.upsertPhoto(photo.copy(uploadStatus = "UPLOADING", lastUploadError = null))

        return runCatching {
            val presign = photosApi.presign(buildPresignRequest("shipment", photo.shipmentId, photo, blob))
            doUpload(blob = blob, thumb = photo.localThumbPath?.let(::File), presign = presign, contentType = photo.contentType)
            // См. комментарий в uploadDeliveryPhoto: confirm идёт по серверному photoId.
            confirmIfNeeded(presign.photoId, presign)
            // См. uploadDeliveryPhoto — после UPLOADED локальный blob/thumb
            // не нужны, чистим, чтобы не накапливать гигабайты на устройстве.
            val updated = photo.copy(
                id = presign.photoId,
                s3Key = presign.s3Key,
                thumbS3Key = presign.thumbS3Key,
                uploadStatus = "UPLOADED",
                uploadedAt = java.time.Instant.now().toString(),
                lastUploadError = null,
                localBlobPath = null,
                localThumbPath = null,
            )
            if (presign.photoId != photo.id) {
                shipmentDao.deletePhotoById(photo.id)
            }
            shipmentDao.upsertPhoto(updated)
            deleteLocalBlobsQuietly(photo.localBlobPath, photo.localThumbPath)
            UploadOutcome.Uploaded
        }.getOrElse { error ->
            // См. uploadDeliveryPhoto: отмену не глотаем и не помечаем как сбой.
            if (error is CancellationException) throw error
            if (isForeignSite(error)) {
                quarantine.quarantineShipmentPhoto(photo)
                return UploadOutcome.Quarantined
            }
            Sentry.withScope { scope ->
                scope.setTag("phase", "photo_upload")
                scope.setTag("parent", "shipment")
                Sentry.captureException(error)
            }
            shipmentDao.upsertPhoto(
                photo.copy(
                    uploadStatus = RemotePhotoStatus.UPLOAD_ERROR,
                    lastUploadError = error.message ?: "upload failed",
                ),
            )
            UploadOutcome.Failed
        }
    }

    /**
     * Удаляет локальные blob/thumb-файлы фотографии после успешного UPLOADED.
     * runCatching — это best-effort cleanup: если файл уже удалён внешним
     * процессом или путь невалидный, ничего не падает.
     */
    private fun deleteLocalBlobsQuietly(blobPath: String?, thumbPath: String?) {
        runCatching { blobPath?.let { File(it).delete() } }
        runCatching { thumbPath?.let { File(it).delete() } }
    }

    private suspend fun isParentReady(parentType: String, parentId: String, parentVersion: Int): Boolean {
        if (parentVersion <= 0) return false
        val pendingMutations = mutationDao.findFor(parentType, parentId)
        if (pendingMutations.any { it.operation == "upsert" && !it.conflictPending }) return false
        return true
    }

    private fun buildPresignRequest(
        operationKind: String,
        operationId: String,
        photo: RemoteDeliveryPhotoEntity,
        @Suppress("UNUSED_PARAMETER") blob: File,
    ): PhotoPresignRequest = PhotoPresignRequest(
        operationKind = operationKind,
        operationId = operationId,
        kind = photo.kind,
        contentHash = photo.contentHash ?: error("contentHash отсутствует для photo ${photo.id}"),
        idempotencyKey = photo.idempotencyKey,
        contentType = photo.contentType,
        thumbContentHash = null, // thumb-hash не считаем — для дедупликации хватает main
        stage = photo.stage,
        // Время съёмки — из Room-строки, файл не перечитываем: он мог быть
        // перезаписан или удалён, а нам нужен момент съёмки, а не отправки.
        takenAt = photo.takenAt,
    )

    private fun buildPresignRequest(
        operationKind: String,
        operationId: String,
        photo: RemoteShipmentPhotoEntity,
        @Suppress("UNUSED_PARAMETER") blob: File,
    ): PhotoPresignRequest = PhotoPresignRequest(
        operationKind = operationKind,
        operationId = operationId,
        kind = photo.kind,
        contentHash = photo.contentHash ?: error("contentHash отсутствует для photo ${photo.id}"),
        idempotencyKey = photo.idempotencyKey,
        contentType = photo.contentType,
        thumbContentHash = null,
        stage = photo.stage,
        takenAt = photo.takenAt,
    )

    private suspend fun doUpload(blob: File, thumb: File?, presign: PhotoPresignResponse, contentType: String) {
        // alreadyExists=true сервер возвращает по совпадению contentHash, но запись
        // может быть orphan (uploadedAt=null — предыдущий PUT упал и не дошёл до S3).
        // Если сервер всё-таки прислал uploadUrl — делаем PUT (overwrite безопасен),
        // иначе пропускаем (файл уже в S3, повторно грузить нечего).
        val uploadUrl = presign.uploadUrl
        if (uploadUrl.isNullOrBlank()) {
            if (!presign.alreadyExists) error("uploadUrl=null при alreadyExists=false")
            return
        }
        putToS3(uploadUrl, blob, contentType, presign.photoId)

        val thumbUploadUrl = presign.thumbUploadUrl
        if (thumb != null && thumb.exists() && !thumbUploadUrl.isNullOrBlank()) {
            runCatching { putToS3(thumbUploadUrl, thumb, contentType, "${presign.photoId}/thumb") }
                // thumb не критичен — ошибка не валит основной upload. Но отмену
                // глотать нельзя: иначе снятие воркера превратится в «thumb не
                // залился» и цикл поедет дальше уже отменённым.
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun putToS3(url: String, file: File, contentType: String, photoId: String) =
        s3Uploader.put(url, file, contentType, photoId)

    private suspend fun confirmIfNeeded(photoId: String, presign: PhotoPresignResponse) {
        // Confirm делаем всегда: сервер сам коротит на uploadedAt!=null и не лезет
        // в S3, а для orphan-записи (uploadedAt=null) проверит HEAD и проставит
        // uploadedAt=now() — иначе через час cleanup-job снесёт запись.
        // Раньше при alreadyExists=true мобила скипала confirm, и записи с
        // тем же contentHash, но без подтверждения, висели orphan'ами.
        @Suppress("UNUSED_PARAMETER") val unused = presign
        photosApi.confirm(photoId)
    }

    private enum class UploadOutcome { Uploaded, Skipped, Failed, Quarantined }

    data class PhotoUploadResult(
        val uploaded: Int,
        val skipped: Int,
        val failed: Int,
        /** Ушли в терминальный карантин (403 foreign_site) — ждут решения человека. */
        val quarantined: Int = 0,
    )
}
