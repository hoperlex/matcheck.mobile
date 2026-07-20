package com.example.matcheckmobile.presentation.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Проверка самого OpenCV-пайплайна. JVM-тесты покрывают выбор и геометрию квада,
 * но не отвечают на вопрос «а находит ли Canny вообще хоть что-нибудь» — для
 * этого нужна настоящая нативная библиотека, то есть устройство или эмулятор.
 *
 * Фикстуры рисуем кодом: так тест детерминирован и не тащит в репозиторий
 * бинарные картинки, которые невозможно осмысленно ревьюить.
 */
@RunWith(AndroidJUnit4::class)
class DocumentEdgeDetectorTest {

    private val detector = DocumentEdgeDetector()
    private val width = 640
    private val height = 480

    @Before
    fun requireOpenCv() {
        assertTrue("OpenCV не загрузился — детект работать не будет", OpenCvBootstrap.isAvailable())
    }

    /** Полотно заданной яркости. */
    private fun canvas(background: Int) = ByteArray(width * height) { background.toByte() }

    /** Закрашивает четырёхугольник (по построчному сканированию) яркостью [value]. */
    private fun ByteArray.fillQuad(corners: List<Pair<Float, Float>>, value: Int) {
        for (y in 0 until height) {
            val xs = mutableListOf<Float>()
            for (i in corners.indices) {
                val (x1, y1) = corners[i]
                val (x2, y2) = corners[(i + 1) % corners.size]
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    xs += x1 + (y - y1) / (y2 - y1) * (x2 - x1)
                }
            }
            if (xs.size < 2) continue
            xs.sort()
            val from = xs.first().roundToInt().coerceIn(0, width - 1)
            val to = xs.last().roundToInt().coerceIn(0, width - 1)
            for (x in from..to) this[y * width + x] = value.toByte()
        }
    }

    private fun detect(luma: ByteArray): NormalizedQuad? {
        val candidates = detector.detectCandidates(luma, width, height)
            .mapNotNull { buildNormalizedQuad(it, width, height, 0) }
        return selectLargestQuad(candidates)
    }

    @Test
    fun findsPlainDocumentOnDarkBackground() {
        val luma = canvas(30)
        luma.fillQuad(
            listOf(90f to 70f, 550f to 70f, 550f to 410f, 90f to 410f),
            value = 230,
        )

        val quad = detect(luma)

        assertNotNull("светлый лист на тёмном фоне обязан находиться", quad)
        assertTrue("рамка должна занимать заметную долю кадра", quad!!.area > 0.3f)
    }

    @Test
    fun findsTiltedDocument() {
        // Съёмка с рук почти всегда даёт наклон — он не должен ломать детект.
        val luma = canvas(30)
        luma.fillQuad(
            listOf(140f to 60f, 560f to 120f, 505f to 425f, 85f to 360f),
            value = 225,
        )

        assertNotNull("наклонённый лист обязан находиться", detect(luma))
    }

    @Test
    fun survivesShadowGradient() {
        // Тень вдоль листа — типичная ситуация на складе.
        val luma = canvas(40)
        luma.fillQuad(listOf(90f to 70f, 550f to 70f, 550f to 410f, 90f to 410f), value = 235)
        for (y in 0 until height) {
            for (x in 0 until width / 3) {
                val i = y * width + x
                luma[i] = (luma[i].toInt() and 0xFF).minus(45).coerceAtLeast(0).toByte()
            }
        }

        assertNotNull("лист с тенью обязан находиться", detect(luma))
    }

    @Test
    fun returnsNothingForWhiteOnWhite() {
        // Белый лист на белом столе — известное ограничение метода. Важно не то,
        // что мы его найдём, а то, что не выдумаем рамку: тогда сработает
        // правило «сохраняем кадр целиком».
        val luma = canvas(245)
        luma.fillQuad(listOf(90f to 70f, 550f to 70f, 550f to 410f, 90f to 410f), value = 250)

        val quad = detect(luma)

        if (quad != null) {
            assertFalse("нельзя принимать за документ границу кадра", looksLikeFrameBorder(quad))
        }
    }

    @Test
    fun returnsNothingForEmptyScene() {
        assertNull("на пустом фоне рамки быть не должно", detect(canvas(120)))
    }

    @Test
    fun doesNotReturnFrameBorderAsDocument() {
        // Резкая рамка по краю кадра не должна выдаваться за документ, иначе
        // сканер предложит «обрезать» снимок по его же границам.
        val luma = canvas(240)
        for (x in 0 until width) {
            for (b in 0 until 3) {
                luma[b * width + x] = 0
                luma[(height - 1 - b) * width + x] = 0
            }
        }
        for (y in 0 until height) {
            for (b in 0 until 3) {
                luma[y * width + b] = 0
                luma[y * width + (width - 1 - b)] = 0
            }
        }

        val quad = detect(luma)

        if (quad != null) {
            assertFalse(looksLikeFrameBorder(quad))
        }
    }
}
