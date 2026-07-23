package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Нормализует ориентацию bitmap согласно EXIF orientation tag, возвращая
 * физически повёрнутый/отзеркаленный bitmap. Покрывает все 8 EXIF-вариантов
 * (включая редкие TRANSPOSE/TRANSVERSE), чтобы фото на Samsung-планшетах
 * One UI (Android 16) после watermark не оставались "запечёнными" в чужой
 * ориентации.
 *
 * Контракт:
 *  - Для ORIENTATION_NORMAL / ORIENTATION_UNDEFINED возвращает исходный
 *    [bitmap] **тем же объектом** (без копии и без recycle).
 *  - Для прочих значений возвращает НОВЫЙ bitmap. Вызывающий код обязан
 *    `recycle()` исходный, если `result !== bitmap`.
 *
 * Используется в [MetadataWatermark] перед отрисовкой штампа, чтобы пиксели
 * соответствовали тому, что увидит пользователь после сохранения файла с
 * EXIF orientation, сброшенным в NORMAL.
 */
internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL,
        ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        // TRANSPOSE = поворот на 90° + горизонтальный flip.
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        // TRANSVERSE = поворот на -90° (270°) + горизонтальный flip.
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private const val NORMALIZE_JPEG_QUALITY = 95

/**
 * Физически «запекает» EXIF-ориентацию [file] в пиксели и сбрасывает тег в
 * NORMAL — чтобы viewer'ы, которые EXIF не уважают (веб-портал), показывали
 * фото ровно. Покрывает все 8 вариантов через [applyExifOrientation], поэтому
 * работает в любой ориентации планшета (портрет и ландшафт).
 *
 * **Безопасно для данных (транзакционно):** оригинал не трогаем, пока корректный
 * результат не собран целиком. Пишем во временный файл в том же каталоге и
 * подменяем оригинал атомарным [Os.rename] ТОЛЬКО после полного успеха. При
 * любой осечке (не-JPEG, compress=false, IO, saveAttributes, rename) удаляем
 * лишь временный файл — оригинал остаётся байт-в-байт прежним. Best-effort:
 * если выпрямить не удалось, фото всё равно сохраняется (пусть с EXIF-тегом).
 *
 * No-op, если тег уже NORMAL/UNDEFINED — лишнего перекодирования нет.
 *
 * Public (не internal) намеренно: инструментальный тест ([androidTest]) — отдельный
 * модуль и internal-члены main не видит.
 */
fun normalizeExifOrientationInPlace(file: File) {
    if (!file.exists() || file.length() == 0L) return

    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) {
        return
    }

    // Битый/не-JPEG вход → оригинал не трогаем.
    val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return
    var oriented: Bitmap? = null
    // Тот же каталог → та же ФС → rename атомарен.
    val tmp = File(file.parentFile, "${file.name}.rot.tmp")
    try {
        oriented = applyExifOrientation(raw, orientation)
        val ok = FileOutputStream(tmp).use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, NORMALIZE_JPEG_QUALITY, out)
        }
        if (!ok) {
            tmp.delete()
            return
        }
        // Пиксели уже физически повёрнуты → тег в NORMAL, иначе viewer повернёт второй раз.
        ExifInterface(tmp.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            saveAttributes()
        }
        Os.rename(tmp.absolutePath, file.absolutePath)
    } catch (t: Throwable) {
        // Оригинал не тронут; убираем только временный файл.
        runCatching { if (tmp.exists()) tmp.delete() }
    } finally {
        if (oriented != null && oriented !== raw) oriented.recycle()
        raw.recycle()
    }
}
