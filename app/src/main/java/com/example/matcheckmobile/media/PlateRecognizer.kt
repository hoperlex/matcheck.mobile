package com.example.matcheckmobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.domain.ocr.PlateOcr
import com.example.matcheckmobile.domain.validation.OcrBlock
import com.example.matcheckmobile.domain.validation.OcrElement
import com.example.matcheckmobile.domain.validation.OcrLine
import com.example.matcheckmobile.domain.validation.OcrRect
import com.example.matcheckmobile.domain.validation.SelectedPlate
import com.example.matcheckmobile.domain.validation.buildCandidates
import com.example.matcheckmobile.domain.validation.cropRect
import com.example.matcheckmobile.domain.validation.pickPlate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "PlateRecognizer"

/**
 * Лимит длинной стороны для ПЕРВОГО прохода. Его задача — найти номер на кадре,
 * а не прочитать его точно; точное чтение делает второй проход по вырезанной
 * рамке в полном разрешении.
 */
private const val OCR_MAX_SIDE = 2048

/**
 * Распознавание госномера через ML Kit Text Recognition (unbundled: модель живёт
 * в Google Play services, в APK её нет).
 *
 * **Два прохода.** Первый ищет номер на уменьшенном кадре. Второй перечитывает
 * найденную рамку в исходном разрешении — цифры кода региона физически мельче
 * серии, и именно на них ошибался одиночный проход (в бой уехал `М583МУ792`
 * вместо `М583МУ799`). Номер подставляется, только если оба прохода прочитали
 * **одно и то же**.
 *
 * Это существенно снижает число ложных подстановок, но не гарантирует их
 * отсутствие: одна и та же модель может дважды ошибиться одинаково. Поэтому
 * правило и выбрано «лучше не подставить» — при любом расхождении поле остаётся
 * пустым, а инспектор вводит номер руками.
 *
 * **Жизненный цикл [recognizer].** [TextRecognizer] — `Closeable`, но мы его
 * сознательно не закрываем: инстанс один на процесс и живёт до его смерти, как
 * `locationProvider` и `metadataWatermark` в AppContainer. Закрывать по уходу с
 * экрана значило бы платить переинициализацией за каждое фото.
 */
class PlateRecognizer : PlateOcr {

    /**
     * Публичный — его же принимает `ModuleInstall.deferredInstall` в AppContainer:
     * [TextRecognizer] реализует `OptionalModuleApi`.
     */
    val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun readFrame(file: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось прочитать кадр", e)
            null
        }
    }

    override suspend fun recognise(frame: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val (originalWidth, originalHeight) = frameSize(frame) ?: return@withContext null

            // --- проход 1: найти номер на уменьшенном кадре
            val coarse = decodeDownscaled(frame, originalWidth, originalHeight)
                ?: return@withContext null
            val coarseWidth = coarse.width
            val coarseHeight = coarse.height
            val firstText = recogniseBitmap(coarse) ?: return@withContext null
            val first = pickPlate(buildCandidates(firstText.toOcrBlocks()))
            logPass("1", "${coarseWidth}x$coarseHeight", first)
            if (first == null) return@withContext null

            // --- проход 2: перечитать рамку номера в исходном разрешении
            val crop = cropRect(
                bounds = first.bounds,
                decodedWidth = coarseWidth,
                decodedHeight = coarseHeight,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            ) ?: return@withContext null

            val cropped = decodeRegion(frame, crop) ?: return@withContext null
            val secondText = recogniseBitmap(cropped) ?: return@withContext null
            val second = pickPlate(buildCandidates(secondText.toOcrBlocks()))
            logPass("2", "${crop.width}x${crop.height}", second)

            val agreed = second != null && second.canonical == first.canonical
            if (BuildConfig.DEBUG) {
                Log.d(TAG, if (agreed) "проходы совпали: ${first.canonical}" else "проходы разошлись — поле не заполняем")
            }
            if (agreed) first.canonical else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Сюда же приходит MlKitException.UNAVAILABLE, когда модуль `ocr` ещё
            // не приехал из Google Play services: штатная ситуация, не ошибка.
            Log.w(TAG, "распознавание не удалось", e)
            null
        }
    }

    /** Габариты кадра без его декодирования. */
    private fun frameSize(frame: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame, 0, frame.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    private fun decodeDownscaled(frame: ByteArray, width: Int, height: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = chooseSampleSize(width, height, OCR_MAX_SIDE)
        }
        val coarse = BitmapFactory.decodeByteArray(frame, 0, frame.size, opts) ?: return null
        // chooseSampleSize оставляет длинную сторону в [MAX, 2 * MAX), точный
        // размер даёт scaleToMaxSide. Она же освобождает исходник, если вернула
        // новый объект, — освобождать его здесь нельзя, это двойной recycle().
        return scaleToMaxSide(coarse, OCR_MAX_SIDE)
    }

    private fun decodeRegion(frame: ByteArray, crop: OcrRect): Bitmap? {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(frame), false)
            ?: return null
        return try {
            decoder.decodeRegion(Rect(crop.left, crop.top, crop.right, crop.bottom), null)
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    /**
     * Отдаёт битмап распознавателю. Владение переходит Task: освобождать в
     * `finally` после await нельзя — при отмене корутины await возвращается
     * сразу, а Task продолжает читать пиксели и получил бы освобождённую память.
     */
    private suspend fun recogniseBitmap(bitmap: Bitmap): Text? {
        val task: Task<Text> = try {
            // Поворот 0: normalizeExifOrientationInPlace уже выпрямила пиксели
            // в rememberPhotoCapture, до того как путь дошёл до ViewModel.
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).also { started ->
                started.addOnCompleteListener { bitmap.recycle() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bitmap.recycle()
            Log.w(TAG, "не удалось запустить распознавание", e)
            return null
        }
        return try {
            task.awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Битмап освободит listener выше — трогать его здесь нельзя.
            Log.w(TAG, "проход распознавания не удался", e)
            null
        }
    }

    private fun logPass(pass: String, size: String, selected: SelectedPlate?) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "проход $pass · кадр $size · ${selected?.canonical ?: "номер не найден"}" +
            (selected?.let { " · рамка ${it.bounds} вес ${it.weight}" } ?: ""))
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
 * `boundingBox` объявлен nullable, и это не теоретическая возможность — рамку
 * тогда наследуем у родителя (у строки для слова, у блока для строки), а в
 * пределе она пустая: такой кандидат остаётся валидным и может выиграть, если
 * он единственный, но никогда не победит в споре двух разных номеров.
 */
private fun Text.toOcrBlocks(): List<OcrBlock> = textBlocks.map { block ->
    val blockBounds = block.boundingBox.toOcrRect()
    OcrBlock(
        lines = block.lines.map { line ->
            val lineBounds = line.boundingBox?.toOcrRect() ?: blockBounds
            OcrLine(
                text = line.text,
                bounds = lineBounds,
                elements = line.elements.map { element ->
                    OcrElement(element.text, element.boundingBox?.toOcrRect() ?: lineBounds)
                },
            )
        },
    )
}

private fun Rect?.toOcrRect(): OcrRect =
    if (this == null) OcrRect(0, 0, 0, 0) else OcrRect(left, top, right, bottom)
