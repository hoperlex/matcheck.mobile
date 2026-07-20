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
    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        return synchronized(this) {
            available ?: runCatching { OpenCVLoader.initLocal() }
                .onFailure { Log.e(TAG, "OpenCV init failed", it) }
                .getOrDefault(false)
                .also {
                    available = it
                    if (!it) Log.w(TAG, "OpenCV unavailable — сканер работает без рамки")
                }
        }
    }
}

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
    fun detectCandidates(luma: ByteArray, width: Int, height: Int): List<List<QuadPoint>> {
        if (!OpenCvBootstrap.isAvailable()) return emptyList()
        if (width <= 0 || height <= 0 || luma.size < width * height) return emptyList()

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
            contours.mapNotNull { contour -> contour.toQuadOrNull() }
        } catch (t: Throwable) {
            // Детект — вспомогательная функция; его падение не должно ломать съёмку.
            Log.e(TAG, "Edge detection failed", t)
            emptyList()
        } finally {
            contours.forEach { it.release() }
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
        }
    }

    /** Аппроксимирует контур; четырёхугольник → кандидат, остальное → null. */
    private fun MatOfPoint.toQuadOrNull(): List<QuadPoint>? {
        val curve = MatOfPoint2f(*toArray())
        val approx = MatOfPoint2f()
        return try {
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null
            Imgproc.approxPolyDP(curve, approx, APPROX_EPSILON_RATIO * perimeter, true)
            val pts = approx.toArray()
            if (pts.size != 4) null else pts.map { QuadPoint(it.x.toFloat(), it.y.toFloat()) }
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
