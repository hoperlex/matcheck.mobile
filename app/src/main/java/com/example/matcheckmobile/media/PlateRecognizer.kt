package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.domain.ocr.PlateOcr
import com.example.matcheckmobile.domain.validation.OcrBlock
import com.example.matcheckmobile.domain.validation.OcrElement
import com.example.matcheckmobile.domain.validation.OcrLine
import com.example.matcheckmobile.domain.validation.buildCandidates
import com.example.matcheckmobile.domain.validation.pickPlate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "PlateRecognizer"

/**
 * Стартовый лимит длинной стороны кадра, который уходит в распознаватель.
 *
 * 2048 взято из аплоад-пайплайна ([RemotePhotoStorage]), где оно подбиралось под трафик,
 * а не под мелкий номер в общем кадре. Если на дальних кадрах номер систематически не
 * читается — поднимать здесь; платим за это памятью и временем распознавания.
 */
private const val OCR_MAX_SIDE = 2048

/**
 * Распознавание госномера через ML Kit Text Recognition (unbundled-вариант: модель живёт
 * в Google Play services и качается отдельным модулем `ocr`, в APK её нет).
 *
 * Класс делает ровно две вещи — готовит битмап и перекладывает результат ML Kit в модель
 * из PlateParsing.kt. Вся логика, где можно ошибиться, лежит там и покрыта тестами.
 *
 * **Жизненный цикл [recognizer].** [TextRecognizer] — `Closeable`, но мы его сознательно
 * не закрываем: инстанс один на процесс и живёт до его смерти, как `locationProvider` и
 * `metadataWatermark` в AppContainer. Закрывать его по уходу с экрана значило бы платить
 * переинициализацией за каждое фото.
 */
class PlateRecognizer : PlateOcr {

    /**
     * Публичный — его же принимает `ModuleInstall.deferredInstall` в AppContainer:
     * [TextRecognizer] реализует `OptionalModuleApi`.
     */
    val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun decodeForOcr(file: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Сначала bounds — чтобы не тащить в память полный кадр ради его габаритов.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = chooseSampleSize(bounds.outWidth, bounds.outHeight, OCR_MAX_SIDE)
            }
            val coarse = FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            // chooseSampleSize оставляет длинную сторону в [OCR_MAX_SIDE, 2 * OCR_MAX_SIDE),
            // точный размер даёт scaleToMaxSide. Она же освобождает coarse, если вернула
            // новый объект, — освобождать его здесь нельзя, это был бы двойной recycle().
            scaleToMaxSide(coarse, OCR_MAX_SIDE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось декодировать кадр для распознавания", e)
            null
        }
    }

    override suspend fun recognise(bitmap: Bitmap): String? {
        // Габариты снимаем ДО process(): после него битмапом владеет Task, и к моменту
        // возврата из await он уже освобождён своим listener'ом.
        val frameSize = "${bitmap.width}x${bitmap.height}"

        val task: Task<Text> = try {
            // Поворот 0: normalizeExifOrientationInPlace уже выпрямила пиксели в
            // rememberPhotoCapture, до того как путь дошёл до ViewModel.
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).also { started ->
                // С этого момента кадром владеет Task. Освобождать битмап в finally после
                // await нельзя: при отмене корутины await вернётся сразу, а Task продолжит
                // читать пиксели — и получил бы освобождённую память.
                started.addOnCompleteListener { bitmap.recycle() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Task не создан, освобождать больше некому.
            bitmap.recycle()
            Log.w(TAG, "не удалось запустить распознавание", e)
            return null
        }

        val text = try {
            task.awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Битмап освободит listener выше — тут его трогать нельзя.
            // Сюда же приходит MlKitException.UNAVAILABLE, когда модуль `ocr` ещё не
            // приехал из Google Play services: это штатная ситуация, не ошибка.
            Log.w(TAG, "распознавание не удалось", e)
            return null
        }

        val blocks = text.toOcrBlocks()
        val plate = pickPlate(buildCandidates(blocks))

        // Только в debug: по этим строкам на планшете видно, в чём именно промах —
        // распознаватель вообще не прочитал номер (мал OCR_MAX_SIDE, размытие, темно)
        // или прочитал, но разбор его отверг. Без них отличить одно от другого нельзя.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "кадр $frameSize, номер: ${plate ?: "не распознан"}")
            blocks.forEach { block ->
                block.lines.forEach { line -> Log.d(TAG, "  h=${line.height}: ${line.text}") }
            }
        }
        return plate
    }
}

/**
 * Ждёт [Task] из корутины. Своя реализация вместо `kotlinx-coroutines-play-services` —
 * ради одной функции тащить в APK ещё одну библиотеку незачем.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Перекладывает результат ML Kit в модель из PlateParsing.kt.
 *
 * `boundingBox` объявлен nullable, и это не теоретическая возможность — вес кандидата
 * тогда берём у родителя (у строки для слова, у блока для строки), а в пределе 0.
 * Кандидат с весом 0 остаётся валидным и может выиграть, если он единственный, но
 * никогда не победит в сравнении двух разных номеров.
 */
private fun Text.toOcrBlocks(): List<OcrBlock> = textBlocks.map { block ->
    val blockLineHeight = block.boundingBox
        ?.let { box -> box.height() / block.lines.size.coerceAtLeast(1) }
        ?: 0
    OcrBlock(
        lines = block.lines.map { line ->
            val lineHeight = line.boundingBox?.height() ?: blockLineHeight
            OcrLine(
                text = line.text,
                height = lineHeight,
                elements = line.elements.map { element ->
                    OcrElement(element.text, element.boundingBox?.height() ?: lineHeight)
                },
            )
        },
    )
}
