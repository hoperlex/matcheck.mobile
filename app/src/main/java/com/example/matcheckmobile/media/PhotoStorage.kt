package com.example.matcheckmobile.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.matcheckmobile.domain.model.PhotoIntent
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

/**
 * Исходники непригодны для отправки — приёмку создавать нельзя.
 * [problems] — по строке на файл, показывается инспектору как есть.
 */
class PhotoSourceInvalidException(val problems: List<String>) :
    IllegalStateException("Непригодные фото: ${problems.joinToString("; ")}")

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
     * Превращает пути к снятым кадрам в [PhotoIntent]-ы для атомарного upsert.
     *
     * Валидирует КАЖДЫЙ исходник до того, как будет создана durable-строка:
     * файл обязан лежать внутри operation_photos, существовать, читаться и быть
     * непустым. Иначе в БД появилась бы запись, из которой фото уже не собрать —
     * то есть та же потеря, только с видимостью «фото есть».
     *
     * Путь к каталогу проверяется по canonicalPath: путь приходит из состояния
     * формы (черновик переживает перезапуск), и подставить туда чужой файл
     * не должно быть возможно.
     *
     * @throws PhotoSourceInvalidException с перечнем непригодных файлов —
     *   форма показывает это инспектору ДО создания приёмки.
     */
    fun intentsFrom(paths: List<String>, kind: String, stage: String): List<PhotoIntent> {
        val dir = photosDir.canonicalFile
        val problems = mutableListOf<String>()
        val intents = mutableListOf<PhotoIntent>()
        for (path in paths) {
            val file = File(path)
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            val reason = when {
                canonical == null -> "недоступен"
                canonical.parentFile != dir -> "вне каталога фото"
                !canonical.isFile -> "файл не найден"
                !canonical.canRead() -> "нет доступа к файлу"
                canonical.length() <= 0L -> "файл пустой"
                else -> null
            }
            if (reason != null) {
                problems += "${file.name}: $reason"
            } else {
                intents += PhotoIntent(
                    kind = kind,
                    stage = stage,
                    sourcePath = canonical!!.absolutePath,
                    takenAt = photoTakenAtIso(canonical),
                )
            }
        }
        if (problems.isNotEmpty()) throw PhotoSourceInvalidException(problems)
        return intents
    }

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
