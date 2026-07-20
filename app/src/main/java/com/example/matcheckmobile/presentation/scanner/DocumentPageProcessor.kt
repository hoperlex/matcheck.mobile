package com.example.matcheckmobile.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.matcheckmobile.media.applyExifOrientation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

private const val TAG = "DocumentScanner"

/**
 * Обрезка и выпрямление страницы по рамке.
 *
 * Вынесено за интерфейс намеренно: это единственное место сканера, которому
 * нужны Bitmap и OpenCV, и без такого шва тесты транзакции в
 * [DocumentScannerViewModel] пришлось бы гонять на устройстве.
 */
interface DocumentPageProcessor {
    /**
     * Пишет в [target] выпрямленный по [quad] фрагмент [source].
     *
     * Реализация обязана: не трогать [source] и при любой неудаче убрать за
     * собой [target] и вернуть `false`. Исходник — последняя линия обороны,
     * потерять страницу нельзя.
     */
    suspend fun cropToQuad(source: File, target: File, quad: NormalizedQuad): Boolean
}

/** Реализация на OpenCV. */
class OpenCvDocumentPageProcessor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentPageProcessor {

    override suspend fun cropToQuad(source: File, target: File, quad: NormalizedQuad): Boolean =
        withContext(dispatcher) {
            if (!OpenCvBootstrap.isAvailable()) return@withContext false

            var decoded: Bitmap? = null
            var oriented: Bitmap? = null
            var result: Bitmap? = null
            val srcMat = Mat()
            val dstMat = Mat()
            var srcPoints: MatOfPoint2f? = null
            var dstPoints: MatOfPoint2f? = null
            var transform: Mat? = null

            try {
                decoded = decodeDownscaled(source) ?: return@withContext false
                // Пиксели приводим к тому, что видел оператор: рамка снималась с
                // ориентированного превью, и без нормализации EXIF квад лёг бы
                // на повёрнутый кадр.
                oriented = applyExifOrientation(decoded, readOrientation(source))

                val width = oriented.width
                val height = oriented.height
                if (width <= 0 || height <= 0) return@withContext false

                val src = quad.scaleTo(width, height)
                val (targetWidth, targetHeight) = warpTargetSize(quad, width, height, MAX_SIDE)

                Utils.bitmapToMat(oriented, srcMat)
                srcPoints = MatOfPoint2f(
                    Point(src[0].x.toDouble(), src[0].y.toDouble()),
                    Point(src[1].x.toDouble(), src[1].y.toDouble()),
                    Point(src[2].x.toDouble(), src[2].y.toDouble()),
                    Point(src[3].x.toDouble(), src[3].y.toDouble()),
                )
                dstPoints = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(targetWidth - 1.0, 0.0),
                    Point(targetWidth - 1.0, targetHeight - 1.0),
                    Point(0.0, targetHeight - 1.0),
                )
                transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
                if (transform.empty() || transform.type() != CvType.CV_64F) return@withContext false

                Imgproc.warpPerspective(
                    srcMat, dstMat, transform, Size(targetWidth.toDouble(), targetHeight.toDouble()),
                )
                if (dstMat.empty()) return@withContext false

                result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(dstMat, result)

                FileOutputStream(target).use { out ->
                    if (!result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        return@withContext false
                    }
                }
                // Пиксели уже выпрямлены — тег обязан это отражать, иначе
                // downstream повернёт картинку второй раз.
                ExifInterface(target.absolutePath).apply {
                    setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL.toString(),
                    )
                    saveAttributes()
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Crop to quad failed", t)
                false
            } finally {
                srcMat.release()
                dstMat.release()
                srcPoints?.release()
                dstPoints?.release()
                transform?.release()
                result?.recycle()
                // applyExifOrientation возвращает исходник тем же объектом,
                // когда поворот не нужен — тогда recycle делаем один раз.
                if (oriented !== decoded) oriented?.recycle()
                decoded?.recycle()
            }
        }.also { ok ->
            if (!ok) runCatching { if (target.exists()) target.delete() }
        }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Декодирует с понижением до [MAX_SIDE]. CameraX по нашей стратегии может
     * отдать кадр крупнее 2048, а пара Bitmap + Mat на полном разрешении легко
     * даёт пик в сотни мегабайт.
     */
    private fun decodeDownscaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > MAX_SIDE * 2 ||
            bounds.outHeight / sample > MAX_SIDE * 2
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private companion object {
        const val MAX_SIDE = 2048
        const val JPEG_QUALITY = 92
    }
}
