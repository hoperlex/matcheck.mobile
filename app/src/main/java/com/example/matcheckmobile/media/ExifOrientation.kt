package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

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
