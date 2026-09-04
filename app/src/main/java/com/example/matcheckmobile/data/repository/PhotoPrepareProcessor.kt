package com.example.matcheckmobile.data.repository

import android.net.Uri
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import com.example.matcheckmobile.media.RemotePhotoStorage
import com.example.matcheckmobile.monitoring.IncidentRecorder
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Локальная подготовка кадров: `PENDING_PREPARE → PENDING_UPLOAD`.
 *
 * Раньше декод + два JPEG-энкода выполнялись прямо в finalize() формы, ПОСЛЕ
 * того как мутация уже легла в очередь. Падение подготовки (нет места, OOM,
 * битый файл) означало, что приёмка уедет на сервер без фото и без следа
 * (см. [com.example.matcheckmobile.domain.model.PhotoIntent]).
 *
 * Теперь строка фото создаётся вместе с мутацией (PhotoIntent), а тяжёлая
 * работа живёт здесь и ретраится вечно. Сети не требует принципиально:
 * [PhotoUploadProcessor] крутится внутри sync-воркера с
 * `NetworkType.CONNECTED`, и за четверо суток офлайна подготовка не запустилась
 * бы ни разу.
 */
class PhotoPrepareProcessor(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val photoStorage: RemotePhotoStorage,
    /** file → Uri. В проде FileProvider, в тестах — Uri.fromFile. */
    private val toUri: (File) -> Uri,
    /** Свободные байты в filesDir. Параметр ради тестов. */
    private val freeSpaceBytes: () -> Long,
    private val journal: IncidentRecorder? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun processAll(): PhotoPrepareResult {
        var prepared = 0
        var failed = 0
        var skipped = 0

        // Возврат просроченных lease: процесс убили в момент подготовки, и
        // строка осталась PREPARING. Без этого кадр завис бы навсегда.
        val cutoff = now() - LEASE_TTL_MS
        deliveryDao.releaseExpiredPreparing(cutoff)
        shipmentDao.releaseExpiredPreparing(cutoff)

        // Пакетно и по одной строке: один повреждённый файл не должен
        // блокировать остальные кадры той же приёмки.
        for (photo in deliveryDao.findPhotosByStatus(RemotePhotoStatus.PREPARABLE)) {
            val source = photo.sourcePath
            if (source == null) {
                // Нечего готовить и нечем: строка без исходника и без blob.
                deliveryDao.upsertPhoto(
                    photo.copy(
                        uploadStatus = RemotePhotoStatus.PREPARE_ERROR,
                        lastUploadError = NO_SOURCE,
                        preparingSince = null,
                    ),
                )
                failed++
                continue
            }
            if (deliveryDao.claimPhotoForPrepare(photo.id, now()) == 0) {
                skipped++
                continue
            }
            when (val outcome = prepare(photo.id, source)) {
                is PrepareOutcome.Ready -> {
                    deliveryDao.upsertPhoto(
                        photo.copy(
                            contentHash = outcome.contentHash,
                            contentType = outcome.contentType,
                            localBlobPath = outcome.mainPath,
                            localThumbPath = outcome.thumbPath,
                            uploadStatus = RemotePhotoStatus.PENDING_UPLOAD,
                            lastUploadError = null,
                            preparingSince = null,
                        ),
                    )
                    deleteSourceIfUnused(source)
                    prepared++
                }
                is PrepareOutcome.Failed -> {
                    deliveryDao.upsertPhoto(
                        photo.copy(
                            uploadStatus = RemotePhotoStatus.PREPARE_ERROR,
                            lastUploadError = outcome.reason,
                            preparingSince = null,
                        ),
                    )
                    failed++
                }
            }
        }

        for (photo in shipmentDao.findPhotosByStatus(RemotePhotoStatus.PREPARABLE)) {
            val source = photo.sourcePath
            if (source == null) {
                shipmentDao.upsertPhoto(
                    photo.copy(
                        uploadStatus = RemotePhotoStatus.PREPARE_ERROR,
                        lastUploadError = NO_SOURCE,
                        preparingSince = null,
                    ),
                )
                failed++
                continue
            }
            if (shipmentDao.claimPhotoForPrepare(photo.id, now()) == 0) {
                skipped++
                continue
            }
            when (val outcome = prepare(photo.id, source)) {
                is PrepareOutcome.Ready -> {
                    shipmentDao.upsertPhoto(
                        photo.copy(
                            contentHash = outcome.contentHash,
                            contentType = outcome.contentType,
                            localBlobPath = outcome.mainPath,
                            localThumbPath = outcome.thumbPath,
                            uploadStatus = RemotePhotoStatus.PENDING_UPLOAD,
                            lastUploadError = null,
                            preparingSince = null,
                        ),
                    )
                    deleteSourceIfUnused(source)
                    prepared++
                }
                is PrepareOutcome.Failed -> {
                    shipmentDao.upsertPhoto(
                        photo.copy(
                            uploadStatus = RemotePhotoStatus.PREPARE_ERROR,
                            lastUploadError = outcome.reason,
                            preparingSince = null,
                        ),
                    )
                    failed++
                }
            }
        }

        return PhotoPrepareResult(prepared = prepared, failed = failed, skipped = skipped)
    }

    private fun prepare(photoId: String, sourcePath: String): PrepareOutcome {
        val file = File(sourcePath)
        if (!file.isFile || file.length() <= 0L) {
            return PrepareOutcome.Failed("исходник недоступен")
        }
        // Подготовка пишет main + thumb рядом с исходником. Если места нет,
        // кадр обязан остаться в очереди и повториться позже, а не пропасть.
        val free = runCatching { freeSpaceBytes() }.getOrDefault(Long.MAX_VALUE)
        val needed = file.length() + MIN_FREE_SPACE_BYTES
        if (free < needed) {
            journal?.record(
                "photo_prepare_no_space",
                "photoId" to photoId,
                "freeBytes" to free,
                "neededBytes" to needed,
            )
            return PrepareOutcome.Failed("не хватает места (свободно ${free / 1_000_000} МБ)")
        }
        return try {
            val prepared = photoStorage.prepareFromUri(toUri(file), photoId)
            PrepareOutcome.Ready(
                mainPath = prepared.mainFile.absolutePath,
                thumbPath = prepared.thumbFile?.absolutePath,
                contentHash = prepared.mainSha256Hex,
                contentType = prepared.contentType,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // OOM на декоде — штатный исход на слабом планшете; ретрай позже
            // (память освободится) лучше, чем потеря кадра.
            val reason = t.message ?: t::class.simpleName ?: "неизвестная ошибка"
            journal?.record("photo_prepare_failed", "photoId" to photoId, "reason" to reason)
            Sentry.captureException(t)
            PrepareOutcome.Failed(reason)
        }
    }

    /**
     * Исходник удаляем сразу после успешной подготовки, а не после server
     * confirm: иначе весь долгий офлайн рядом лежат и полноразмерный кадр,
     * и его сжатая копия — как раз то, из-за чего на планшете кончается место.
     *
     * Один и тот же файл может быть источником нескольких строк (кадр приложен
     * и к приёмке, и к отгрузке), поэтому удаляем, только когда других
     * неподготовленных строк с этим путём не осталось.
     */
    /**
     * Открыт для форм: снятый и тут же удалённый инспектором кадр раньше
     * оставался в operation_photos навсегда — путь уходил из стейта, а файл нет.
     */
    suspend fun deleteSourceIfUnused(sourcePath: String) {
        val pending = deliveryDao.countAwaitingPrepareForSource(sourcePath) +
            shipmentDao.countAwaitingPrepareForSource(sourcePath)
        if (pending == 0) {
            runCatching { File(sourcePath).delete() }
        }
    }

    private sealed interface PrepareOutcome {
        data class Ready(
            val mainPath: String,
            val thumbPath: String?,
            val contentHash: String,
            val contentType: String,
        ) : PrepareOutcome

        data class Failed(val reason: String) : PrepareOutcome
    }

    private companion object {
        /**
         * Через столько строка в PREPARING считается брошенной. Подготовка
         * одного кадра — секунды; час с запасом покрывает и медленный планшет,
         * и заморозку процесса в Doze.
         */
        const val LEASE_TTL_MS = 60 * 60 * 1000L

        /** Запас сверх размера исходника: main ~1.5 МБ + thumb ~100 КБ + люфт. */
        const val MIN_FREE_SPACE_BYTES = 20L * 1024 * 1024

        const val NO_SOURCE = "нет исходника"
    }
}

data class PhotoPrepareResult(
    val prepared: Int,
    val failed: Int,
    val skipped: Int,
) {
    val isEmpty: Boolean get() = prepared == 0 && failed == 0 && skipped == 0
}
