package com.example.matcheckmobile.presentation.scanner

import android.graphics.BitmapFactory
import com.example.matcheckmobile.media.PhotoStorage
import java.io.File

/**
 * Файловые операции сканера за швом.
 *
 * Нужен ровно затем, чтобы транзакцию кадра (оригинал → проверка → warp →
 * подмена) можно было проверить обычными JVM-тестами: иначе она тянет за собой
 * `Context` через [PhotoStorage] и `BitmapFactory`, и правила владения файлами —
 * самое опасное место для данных — остались бы непокрытыми.
 */
interface ScannerFileStore {
    /** Новый файл страницы. Обязан лежать в `operation_photos` (см. FileProvider). */
    fun createPageFile(): File

    /** Проверка, что downstream сможет открыть файл. */
    fun isDecodableJpeg(file: File): Boolean
}

/** Боевая реализация поверх [PhotoStorage]. */
class PhotoStorageFileStore(private val photoStorage: PhotoStorage) : ScannerFileStore {

    override fun createPageFile(): File = photoStorage.createTempFile(DOC_PREFIX)

    /**
     * Только границы, без аллокации пикселей: `length > 0` ничего не гарантирует,
     * а недекодируемый файл всплыл бы лишь на submit — когда терять данные
     * дороже всего.
     */
    override fun isDecodableJpeg(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    private companion object {
        const val DOC_PREFIX = "doc"
    }
}
