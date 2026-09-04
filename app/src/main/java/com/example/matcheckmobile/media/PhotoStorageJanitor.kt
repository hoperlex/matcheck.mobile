package com.example.matcheckmobile.media

import android.util.Log
import com.example.matcheckmobile.data.local.MatcheckDatabase
import java.io.File

private const val TAG = "PhotoStorageJanitor"

/** Ориентир суммарного размера сохранённых локальных миниатюр. */
private const val DEFAULT_MAX_THUMB_BYTES = 300L * 1024 * 1024

/**
 * Держит локальное хранилище кадров в границах.
 *
 * **Инвариант, принятый явно: миниатюра своего фото НЕ удаляется по возрасту.**
 * Она исчезает только при удалении операции, logout-wipe или здесь — вытеснением
 * по общему лимиту каталога, самые давно не открывавшиеся первыми. Правило «не
 * по возрасту» и есть то, что держит обещание «свои фото видно офлайн»: вчерашняя
 * приёмка, которую инспектор дооформляет сегодня, не должна зависеть от S3.
 *
 * Трогаем только миниатюры уже отправленных фото. Main-blob'ы и миниатюры
 * неотправленных кадров принадлежат pipeline'у загрузки — их удаление потеряло бы
 * работу инспектора.
 */
class PhotoStorageJanitor(
    private val database: MatcheckDatabase,
    private val diskCache: PhotoDiskCache,
    private val maxThumbBytes: Long = DEFAULT_MAX_THUMB_BYTES,
) {

    suspend fun run() {
        runCatching { evictLocalThumbs() }
            .onFailure { Log.w(TAG, "вытеснение миниатюр не удалось", it) }
        runCatching { diskCache.evictIfNeeded() }
            .onFailure { Log.w(TAG, "вытеснение кэша не удалось", it) }
    }

    private suspend fun evictLocalThumbs() {
        val deliveryDao = database.remoteDeliveryDao()
        val shipmentDao = database.remoteShipmentDao()

        data class Candidate(val file: File, val clear: suspend () -> Unit)

        val candidates = buildList {
            deliveryDao.findUploadedLocalThumbs().forEach { ref ->
                val path = ref.localThumbPath ?: return@forEach
                add(Candidate(File(path)) { deliveryDao.clearLocalThumb(ref.id) })
            }
            shipmentDao.findUploadedLocalThumbs().forEach { ref ->
                val path = ref.localThumbPath ?: return@forEach
                add(Candidate(File(path)) { shipmentDao.clearLocalThumb(ref.id) })
            }
        }

        // Строки, чей файл уже исчез (системная чистка, ручное удаление), чиним
        // сразу: путь в базе на несуществующий файл — это «фото недоступно» в UI
        // вместо честного похода в S3.
        candidates.filterNot { it.file.exists() }.forEach { it.clear() }

        val alive = candidates.filter { it.file.exists() }
        var total = alive.sumOf { it.file.length() }
        if (total <= maxThumbBytes) return

        Log.i(TAG, "миниатюры заняли $total B при лимите $maxThumbBytes B — вытесняем")
        alive.sortedBy { it.file.lastModified() }.forEach { candidate ->
            if (total <= maxThumbBytes) return
            val size = candidate.file.length()
            if (candidate.file.delete()) {
                total -= size
                candidate.clear()
            }
        }
    }
}
