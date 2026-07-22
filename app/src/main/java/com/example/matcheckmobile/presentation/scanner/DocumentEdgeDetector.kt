package com.example.matcheckmobile.presentation.scanner

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val TAG = "DocumentScanner"

/**
 * Загрузка нативной части OpenCV. Ленивая и однократная.
 *
 * Сбой загрузки не должен ронять сканер: камера и ручной затвор работают и без
 * детекта, просто не будет рамки. Инспектор в поле снимет документ в любом
 * случае — это важнее красивой обводки.
 */
object OpenCvBootstrap {
    /** Трёхзначное состояние для HUD: пока детект не вызывали — PENDING. */
    enum class State { PENDING, OK, FAIL }

    @Volatile
    var state: State = State.PENDING
        private set

    fun isAvailable(): Boolean {
        if (state != State.PENDING) return state == State.OK
        return synchronized(this) {
            if (state != State.PENDING) return state == State.OK
            val ok = runCatching { OpenCVLoader.initLocal() }
                .onFailure { Log.e(TAG, "OpenCV init failed", it) }
                .getOrDefault(false)
            state = if (ok) State.OK else State.FAIL
            Log.i(TAG, "OpenCV initLocal=$ok")
            if (!ok) Log.w(TAG, "OpenCV unavailable — сканер работает без рамки")
            ok
        }
    }
}

/**
 * Кадры-кандидаты + счётчики для диагностики. Именно из-за неразличимости
 * «детект упал» и «контуров нет» (обе давали пустой список) в HUD нужен
 * отдельный [error].
 *
 * @param raw   всего найденных контуров
 * @param exact4 контуры, аппроксимированные ровно в 4 точки (кандидаты)
 * @param near4 контуры с 5–6 точками — прямая проверка гипотезы «требуем ровно 4»
 * @param error детект бросил исключение (а не просто ничего не нашёл)
 */
data class DetectResult(
    val candidates: List<List<QuadPoint>>,
    val raw: Int,
    val exact4: Int,
    val near4: Int,
    val error: Boolean,
)

/**
 * OpenCV-слой детекта: из полутонового кадра достаёт контуры-кандидаты.
 *
 * Слой намеренно «глупый» — он только ищет четырёхугольники и отдаёт их в
 * простых точках. Вся оценка (выпуклость, площадь, порядок углов, отбраковка
 * рамки кадра, стабилизация) живёт в чистом [DocumentQuad], который можно
 * гонять JVM-тестами без эмулятора.
 */
class DocumentEdgeDetector {

    /**
     * @param luma плотно упакованный серый кадр (см. [cropLuma])
     * @return кандидаты в **crop-local неповёрнутых** координатах
     */
    fun detectCandidates(luma: ByteArray, width: Int, height: Int): List<List<QuadPoint>> =
        detectWithStats(luma, width, height).candidates

    /**
     * То же, что [detectCandidates], но со счётчиками для диагностического HUD.
     * Поведение поиска идентично — считаем лишь дополнительную статистику.
     */
    fun detectWithStats(luma: ByteArray, width: Int, height: Int): DetectResult {
        val empty = DetectResult(emptyList(), raw = 0, exact4 = 0, near4 = 0, error = false)
        if (!OpenCvBootstrap.isAvailable()) return empty
        if (width <= 0 || height <= 0 || luma.size < width * height) return empty

        val gray = Mat(height, width, CvType.CV_8UC1)
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        return try {
            gray.put(0, 0, luma)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)
            // Лёгкое расширение смыкает разрывы контура: у листа на светлом
            // столе граница часто рвётся, и без этого квад не замыкается.
            Imgproc.dilate(edges, edges, Mat(), org.opencv.core.Point(-1.0, -1.0), 1)
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
            )
            var near4 = 0
            val candidates = mutableListOf<List<QuadPoint>>()
            for (contour in contours) {
                val approx = contour.approxVertices() ?: continue
                if (approx.size == 4) {
                    candidates += approx.map { QuadPoint(it.first, it.second) }
                } else if (approx.size in 5..6) {
                    near4++
                }
            }
            DetectResult(
                candidates = candidates,
                raw = contours.size,
                exact4 = candidates.size,
                near4 = near4,
                error = false,
            )
        } catch (t: Throwable) {
            // Детект — вспомогательная функция; его падение не должно ломать съёмку.
            // error=true, чтобы HUD отличил «упал» от «контуров нет».
            Log.e(TAG, "Edge detection failed", t)
            empty.copy(error = true)
        } finally {
            contours.forEach { it.release() }
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
        }
    }

    /** Аппроксимация контура: список вершин или null (пустой/вырожденный периметр). */
    private fun MatOfPoint.approxVertices(): List<Pair<Float, Float>>? {
        val curve = MatOfPoint2f(*toArray())
        val approx = MatOfPoint2f()
        return try {
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null
            Imgproc.approxPolyDP(curve, approx, APPROX_EPSILON_RATIO * perimeter, true)
            approx.toArray().map { it.x.toFloat() to it.y.toFloat() }
        } catch (t: Throwable) {
            null
        } finally {
            curve.release()
            approx.release()
        }
    }

    private companion object {
        const val CANNY_LOW = 60.0
        const val CANNY_HIGH = 180.0

        /** Доля периметра для approxPolyDP: меньше — точнее, больше — грубее. */
        const val APPROX_EPSILON_RATIO = 0.02
    }
}
