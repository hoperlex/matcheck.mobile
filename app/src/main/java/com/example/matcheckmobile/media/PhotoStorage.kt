package com.example.matcheckmobile.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Атомарно создаёт пустой уникальный файл в [dir] с именем вида
 * `<prefix>_<штамп>_<случайное>.jpg`.
 *
 * Штамп входит в префикс, а не идёт отдельным сегментом: `File.createTempFile`
 * требует префикс длиной не меньше трёх символов, а вызывающий код передаёт в
 * том числе двухсимвольный «op». Со штампом длина безопасна по построению —
 * ограничение не всплывёт снова, если кто-то позовёт с коротким префиксом.
 *
 * Вынесено из [PhotoStorage] верхнеуровневой функцией, чтобы покрывалось
 * обычным JVM-тестом: самому классу нужен Context ради filesDir.
 */
internal fun createUniquePhotoFile(dir: File, prefix: String): File {
    if (!dir.exists()) dir.mkdirs()
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return File.createTempFile("${prefix}_${stamp}_", ".jpg", dir)
}

class PhotoStorage(private val context: Context) {
    private val photosDir: File
        get() = File(context.filesDir, "operation_photos").apply { if (!exists()) mkdirs() }

    /**
     * Раньше имя собиралось из отметки времени с точностью до миллисекунды, а файл
     * не создавался. Сканер импортирует страницы циклом, и совпадение миллисекунд
     * давало две страницы с одним именем: вторая затирала первую, а в черновик
     * уходили два одинаковых пути — на портал вместо разных страниц уезжали дубли.
     */
    fun createTempFile(prefix: String = "op"): File = createUniquePhotoFile(photosDir, prefix)

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
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Не удалось открыть поток для $uri")
        } catch (t: Throwable) {
            // createTempFile теперь создаёт файл сразу, поэтому сбой копирования
            // оставил бы за собой пустой файл-сироту: чистим за собой.
            dst.delete()
            throw t
        }
        return dst
    }
}
