package com.example.matcheckmobile.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoStorage(private val context: Context) {
    private val photosDir: File
        get() = File(context.filesDir, "operation_photos").apply { if (!exists()) mkdirs() }

    fun createTempFile(prefix: String = "op"): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(photosDir, "${prefix}_${stamp}.jpg")
    }

    fun toContentUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )

    /**
     * Копирует содержимое content-URI в новый файл в локальной директории фото.
     * Используется для импорта результатов ML Kit Document Scanner — он отдаёт
     * страницы как content-URI своего FileProvider, доступные только в рамках
     * текущей сессии разрешений; чтобы дальше работать со стандартным pipeline,
     * нужно перенести байты к себе.
     */
    fun importFromUri(uri: Uri, prefix: String = "doc"): File {
        val dst = createTempFile(prefix = prefix)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось открыть поток для $uri")
        return dst
    }
}
