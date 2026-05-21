package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.remote.api.PhotosApi
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignRequest
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

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
) {

    /** Plain OkHttpClient без auth-interceptor-ов — для PUT по presigned-ссылке. */
    private val rawS3Client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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

        val deliveryPhotos = deliveryDao.findPhotosByStatus(
            listOf("PENDING_UPLOAD", "UPLOAD_ERROR"),
        )
        for (photo in deliveryPhotos) {
            when (uploadDeliveryPhoto(photo)) {
                UploadOutcome.Uploaded -> uploaded++
                UploadOutcome.Skipped -> skipped++
                UploadOutcome.Failed -> failed++
            }
        }

        val shipmentPhotos = shipmentDao.findPhotosByStatus(
            listOf("PENDING_UPLOAD", "UPLOAD_ERROR"),
        )
        for (photo in shipmentPhotos) {
            when (uploadShipmentPhoto(photo)) {
                UploadOutcome.Uploaded -> uploaded++
                UploadOutcome.Skipped -> skipped++
                UploadOutcome.Failed -> failed++
            }
        }

        return PhotoUploadResult(uploaded = uploaded, skipped = skipped, failed = failed)
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
            val updated = photo.copy(
                id = presign.photoId,
                s3Key = presign.s3Key,
                thumbS3Key = presign.thumbS3Key,
                uploadStatus = "UPLOADED",
                uploadedAt = java.time.Instant.now().toString(),
                lastUploadError = null,
            )
            if (presign.photoId != photo.id) {
                // Меняем PK — Room не умеет это через upsert, удаляем старую и вставляем новую.
                deliveryDao.deletePhotoById(photo.id)
            }
            deliveryDao.upsertPhoto(updated)
            UploadOutcome.Uploaded
        }.getOrElse { error ->
            deliveryDao.upsertPhoto(
                photo.copy(uploadStatus = "UPLOAD_ERROR", lastUploadError = error.message ?: "upload failed"),
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
            val updated = photo.copy(
                id = presign.photoId,
                s3Key = presign.s3Key,
                thumbS3Key = presign.thumbS3Key,
                uploadStatus = "UPLOADED",
                uploadedAt = java.time.Instant.now().toString(),
                lastUploadError = null,
            )
            if (presign.photoId != photo.id) {
                shipmentDao.deletePhotoById(photo.id)
            }
            shipmentDao.upsertPhoto(updated)
            UploadOutcome.Uploaded
        }.getOrElse { error ->
            shipmentDao.upsertPhoto(
                photo.copy(uploadStatus = "UPLOAD_ERROR", lastUploadError = error.message ?: "upload failed"),
            )
            UploadOutcome.Failed
        }
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
    )

    private fun doUpload(blob: File, thumb: File?, presign: PhotoPresignResponse, contentType: String) {
        // alreadyExists=true сервер возвращает по совпадению contentHash, но запись
        // может быть orphan (uploadedAt=null — предыдущий PUT упал и не дошёл до S3).
        // Если сервер всё-таки прислал uploadUrl — делаем PUT (overwrite безопасен),
        // иначе пропускаем (файл уже в S3, повторно грузить нечего).
        val uploadUrl = presign.uploadUrl
        if (uploadUrl.isNullOrBlank()) {
            if (!presign.alreadyExists) error("uploadUrl=null при alreadyExists=false")
            return
        }
        putToS3(uploadUrl, blob, contentType)

        val thumbUploadUrl = presign.thumbUploadUrl
        if (thumb != null && thumb.exists() && !thumbUploadUrl.isNullOrBlank()) {
            runCatching { putToS3(thumbUploadUrl, thumb, contentType) }
            // thumb не критичен — ошибка не валит основной upload.
        }
    }

    private fun putToS3(url: String, file: File, contentType: String) {
        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        rawS3Client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Захватываем тело ответа S3: оно содержит XML с <Code>/<Message>
                // (SignatureDoesNotMatch, AccessDenied, NoSuchBucket и т.п.).
                // Без этого диагностировать неудачный PUT по одному HTTP-коду
                // невозможно — особенно через прокси s3.cloud.ru, который
                // может возвращать 403/400 по разным причинам.
                val body = runCatching { response.body?.string()?.take(400) }.getOrNull()
                val host = response.request.url.host
                val msg = "S3 PUT $host failed: HTTP ${response.code}${body?.let { " · $it" } ?: ""}"
                android.util.Log.e("PhotoUpload", msg)
                throw IOException(msg)
            }
        }
    }

    private suspend fun confirmIfNeeded(photoId: String, presign: PhotoPresignResponse) {
        // Confirm делаем всегда: сервер сам коротит на uploadedAt!=null и не лезет
        // в S3, а для orphan-записи (uploadedAt=null) проверит HEAD и проставит
        // uploadedAt=now() — иначе через час cleanup-job снесёт запись.
        // Раньше при alreadyExists=true мобила скипала confirm, и записи с
        // тем же contentHash, но без подтверждения, висели orphan'ами.
        @Suppress("UNUSED_PARAMETER") val unused = presign
        photosApi.confirm(photoId)
    }

    private enum class UploadOutcome { Uploaded, Skipped, Failed }

    data class PhotoUploadResult(
        val uploaded: Int,
        val skipped: Int,
        val failed: Int,
    )
}
