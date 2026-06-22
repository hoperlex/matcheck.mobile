package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Накладывает на JPEG-файл «штамп» в правом нижнем углу с метаданными
 * фиксации: координатами GPS, локальным временем и кратким siteId.
 *
 * Размер шрифта подбирается относительно меньшей стороны кадра, чтобы штамп
 * нормально читался и на мелком phone-фото, и на крупном scanner-output.
 * Перезаписывает исходный файл с качеством JPEG=90.
 */
class MetadataWatermark {

    fun applyTo(file: File, coords: LocationProvider.Coords?, siteName: String?) {
        if (!file.exists() || file.length() == 0L) return

        // EXIF orientation читаем ДО decode'а: BitmapFactory.decodeFile сам тэг
        // не уважает и возвращает «сырые» пиксели сенсора. На планшетах с
        // не-portrait ориентацией (Samsung One UI Android 16, см.
        // [[project_test_device]]) сырой буфер часто landscape / 180°. Если
        // нарисовать watermark и сохранить как есть — viewer'ы (Coil,
        // decodeOrientedBitmap) увидят NORMAL (после нашего ниже сброса)
        // и не повернут. Поэтому нормализуем пиксели физически, ДО штампа,
        // и в конце пишем TAG_ORIENTATION = NORMAL.
        val exifOrientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val oriented = applyExifOrientation(raw, exifOrientation)
        if (oriented !== raw) raw.recycle()

        val bitmap = oriented.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== oriented) oriented.recycle()

        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        val shortSide = min(w, h).toFloat()
        val fontSize = max(28f, shortSide * 0.028f)
        val padding = (shortSide * 0.025f)
        val lineGap = fontSize * 1.35f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
            setShadowLayer(fontSize * 0.18f, 0f, 0f, Color.BLACK)
        }

        val lines = listOf(
            buildGpsLine(coords),
            buildDateTimeLine(),
            buildObjectLine(siteName),
        )
        val maxLineW = lines.maxOf { textPaint.measureText(it) }

        val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
        }
        val rect = RectF(
            w - maxLineW - padding * 2.2f,
            h - (lineGap * lines.size + padding * 1.2f) - padding * 0.5f,
            (w - padding * 0.6f),
            (h - padding * 0.6f),
        )
        canvas.drawRoundRect(rect, fontSize * 0.35f, fontSize * 0.35f, rectPaint)

        val x = w - padding * 1.4f
        lines.forEachIndexed { i, line ->
            val y = h - padding - (lines.size - 1 - i) * lineGap - fontSize * 0.25f
            canvas.drawText(line, x, y, textPaint)
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()

        // После compress пиксели лежат уже физически в правильной ориентации,
        // поэтому EXIF tag сбрасываем в NORMAL — иначе viewer повернёт второй
        // раз (мы уже повернули, а тэг ещё указывал бы на исходную ориентацию,
        // если бы Bitmap.compress его сохранил). runCatching: на странных
        // JPEG'ах метод может бросить — это не должно валить съёмку.
        runCatching {
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                saveAttributes()
            }
        }
    }

    private fun buildGpsLine(coords: LocationProvider.Coords?): String =
        if (coords == null) "GPS: нет"
        else "GPS: %.5f, %.5f".format(coords.lat, coords.lon)

    private fun buildDateTimeLine(): String {
        val now = LocalDateTime.now()
        return "Дата время: ${now.format(TIME_FORMATTER)}"
    }

    private fun buildObjectLine(siteName: String?): String {
        val name = siteName?.takeIf { it.isNotBlank() } ?: "—"
        return "Объект: $name"
    }

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
