package com.example.matcheckmobile.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Готовит фото к загрузке в matcheck API. Сжимает main (max 2048 px,
 * ~1.5 МБ цель) и thumb (max 320 px, ~100 КБ) — компромисс между качеством
 * и трафиком на полевом 4G. Считает SHA-256 для дедупликации на сервере.
 *
 * Файлы кладём в filesDir/remote_photos/, чтобы их не съел системный cleanup,
 * и чтобы они переживали logout (хотя при logout очередь обычно зачищается).
 * Имена — {photoId}.jpg / {photoId}.thumb.jpg.
 */
class RemotePhotoStorage(private val context: Context) {

    private val photosDir: File
        get() = File(context.filesDir, "remote_photos").apply { if (!exists()) mkdirs() }

    /**
     * Берёт картинку по [sourceUri], сжимает её до main + thumb (JPEG q=85),
     * пишет на диск и возвращает результат с SHA-256.
     */
    fun prepareFromUri(sourceUri: Uri, photoId: String): PreparedPhoto {
        val mainBitmap = loadDownsampled(sourceUri, MAIN_MAX_SIDE)
            ?: error("can't decode bitmap from $sourceUri")
        val orientedMain = applyExifRotation(sourceUri, mainBitmap)

        // Порядок важен: сначала пишем main, потом делаем thumb. Если делать
        // наоборот, scaleToMaxSide может зарекайклить orientedMain, и compress
        // в JPEG упадёт с «Can't compress a recycled bitmap».
        val mainFile = File(photosDir, "$photoId.jpg")
        val mainBytes = writeJpeg(orientedMain, mainFile, MAIN_QUALITY)

        val thumbBitmap = scaleToMaxSide(orientedMain, THUMB_MAX_SIDE)
        val thumbFile = File(photosDir, "$photoId.thumb.jpg")
        val thumbBytes = writeJpeg(thumbBitmap, thumbFile, THUMB_QUALITY)

        if (thumbBitmap !== orientedMain) thumbBitmap.recycle()
        if (!orientedMain.isRecycled) orientedMain.recycle()

        return PreparedPhoto(
            mainFile = mainFile,
            mainSha256Hex = sha256Hex(mainBytes),
            mainSizeBytes = mainBytes.size.toLong(),
            thumbFile = thumbFile,
            thumbSha256Hex = sha256Hex(thumbBytes),
            thumbSizeBytes = thumbBytes.size.toLong(),
            contentType = JPEG_MIME,
        )
    }

    fun deleteBlob(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).delete() }
    }

    private fun loadDownsampled(uri: Uri, maxSide: Int): Bitmap? {
        // Сначала bounds, чтобы посчитать inSampleSize и не аллоцировать всю картинку в RAM.
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxSide || boundsOpts.outHeight / sampleSize > maxSide) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        }?.let { scaleToMaxSide(it, maxSide) }
    }

    /**
     * Применяет EXIF-ориентацию через общий [applyExifOrientation], который
     * покрывает все 8 EXIF-вариантов (ROTATE_90/180/270, FLIP_H/V,
     * TRANSPOSE/TRANSVERSE). Раньше здесь был свой switch только по
     * ROTATE_90/180/270 — на редких EXIF tag'ах фото уходило на сервер
     * не нормализованным, и на веб-портале отображалось перевёрнутым.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream != null) ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                ) else ExifInterface.ORIENTATION_NORMAL
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotated = applyExifOrientation(bitmap, orientation)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int): ByteArray {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        return file.readBytes()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class PreparedPhoto(
        val mainFile: File,
        val mainSha256Hex: String,
        val mainSizeBytes: Long,
        val thumbFile: File?,
        val thumbSha256Hex: String?,
        val thumbSizeBytes: Long?,
        val contentType: String,
    )

    private companion object {
        const val MAIN_MAX_SIDE = 2048
        const val THUMB_MAX_SIDE = 320
        const val MAIN_QUALITY = 85
        const val THUMB_QUALITY = 80
        const val JPEG_MIME = "image/jpeg"
    }
}
