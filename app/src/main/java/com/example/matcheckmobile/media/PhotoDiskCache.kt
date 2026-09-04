package com.example.matcheckmobile.media

import android.util.Log
import java.io.File

private const val TAG = "PhotoDiskCache"

/** Ориентир размера кэша скачанных кадров. */
private const val DEFAULT_MAX_BYTES = 120L * 1024 * 1024

/**
 * Дисковый кэш кадров, скачанных из S3.
 *
 * Отвечает ровно за одно: раз показанное фото больше не должно пропадать при
 * следующем открытии экрана — ни от потери сети, ни от того, что объект исчез
 * из S3 (известный инфраструктурный баг cloud.ru). Для СВОИХ фото есть более
 * сильная гарантия — локальная миниатюра рядом с blob'ом; этот кэш нужен для
 * чужих кадров и для полноразмерных просмотров.
 *
 * Лежит в `cacheDir`: система вправе вычистить его при нехватке места, и это
 * корректно — данные восстановимы из S3.
 */
class PhotoDiskCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /** Ключ различает миниатюру и оригинал: это разные байты одного photoId. */
    fun key(photoId: String, thumb: Boolean): String = "$photoId.${if (thumb) "t" else "m"}"

    /**
     * Возвращает файл кэша и **обновляет время доступа**: вытеснение идёт по
     * `lastModified`, и без этого LRU выбрасывал бы как раз то, чем постоянно
     * пользуются.
     */
    fun get(key: String): File? {
        val file = File(dir, key)
        if (!file.exists() || file.length() <= 0L) return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /**
     * Кладёт байты в кэш. Запись атомарна (tmp + rename), как в
     * [RemotePhotoStorage]: оборванная запись не должна оставить обрезанный
     * JPEG, который выглядит как готовый кадр.
     */
    fun put(key: String, bytes: ByteArray) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            try {
                tmp.writeBytes(bytes)
                val target = File(dir, key)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }.onFailure { Log.w(TAG, "не удалось записать кадр в кэш", it) }
        evictIfNeeded()
    }

    /** Вытеснение по общему размеру: самые давно не открывавшиеся уходят первыми. */
    fun evictIfNeeded() {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= maxBytes) return
                val size = file.length()
                if (file.delete()) total -= size
            }
        }.onFailure { Log.w(TAG, "не удалось вытеснить кэш", it) }
    }
}
