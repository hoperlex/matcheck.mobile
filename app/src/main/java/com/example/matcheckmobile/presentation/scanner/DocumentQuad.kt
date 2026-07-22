package com.example.matcheckmobile.presentation.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Чистая геометрия рамки документа: порядок углов, поворот, нормализация,
 * отбраковка и стабилизация.
 *
 * Здесь намеренно нет ни Android, ни OpenCV — только простые точки. Благодаря
 * этому весь нетривиальный счёт (а именно он ломается на поворотах и на границе
 * кадра) проверяется обычными JVM-тестами, без эмулятора.
 */

/** Точка в тех координатах, о которых договорились на конкретном шаге. */
data class QuadPoint(val x: Float, val y: Float)

/**
 * Рамка в нормализованных `[0..1]` координатах **ориентированной видимой**
 * области. Один и тот же квад годится и для Canvas, и для JPEG — разница
 * только в множителе (см. [scaleTo]).
 *
 * Порядок вершин всегда TL → TR → BR → BL.
 */
data class NormalizedQuad(
    val topLeft: QuadPoint,
    val topRight: QuadPoint,
    val bottomRight: QuadPoint,
    val bottomLeft: QuadPoint,
) {
    val points: List<QuadPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Площадь по формуле шнурков; в единичном квадрате это сразу доля кадра. */
    val area: Float get() = polygonArea(points)

    /** Разворачивает нормализованные координаты в пиксели Canvas или bitmap. */
    fun scaleTo(width: Int, height: Int): List<QuadPoint> =
        points.map { QuadPoint(it.x * width, it.y * height) }

    /**
     * Пиксели вершин в системе координат **ориентированного** `cropRect` — вход для
     * CameraX `CoordinateTransform` (source построен с `usingRotationDegrees=true`,
     * поэтому ждёт точки уже после поворота). При 90/270 стороны crop меняются
     * местами, ровно как `getRotatedCropRect` внутри CameraX. Формат —
     * `[x0,y0, x1,y1, x2,y2, x3,y3]`, порядок вершин TL→TR→BR→BL сохранён.
     */
    fun toOrientedCropPixels(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): FloatArray {
        val swap = when (((rotationDegrees % 360) + 360) % 360) {
            90, 270 -> true
            else -> false
        }
        val w = if (swap) cropHeight else cropWidth
        val h = if (swap) cropWidth else cropHeight
        val out = FloatArray(8)
        points.forEachIndexed { i, p ->
            out[i * 2] = p.x * w
            out[i * 2 + 1] = p.y * h
        }
        return out
    }
}

/** Рамка, прошедшая стабилизацию, вместе с моментом её фиксации. */
data class StableQuad(val quad: NormalizedQuad, val detectedAt: Long)

// ── Пороги. Собраны в одном месте: их калибруют на fixture-изображениях, и
// разбросанные по коду «магические» числа сделали бы калибровку мучением.

/** Минимальная доля кадра — меньше похоже на мусор, а не на лист. */
const val MIN_QUAD_AREA = 0.10f

/** Больше этого — почти наверняка поймали не документ, а рамку самого кадра. */
const val MAX_QUAD_AREA = 0.95f

/** Насколько близко к краю кадра должны лежать все углы, чтобы счесть их границей. */
const val FRAME_EDGE_EPS = 0.02f

/** Минимальная длина стороны в долях кадра — отсекает вырожденные фигуры. */
const val MIN_QUAD_SIDE = 0.05f

/** Сколько подряд согласованных кадров нужно, чтобы показать рамку. */
const val STABLE_STREAK = 3

/** Максимальное смещение угла между кадрами, при котором они считаются согласованными. */
const val MAX_CORNER_SHIFT = 0.05f

/** Сколько живёт зафиксированная рамка. Дальше она может не соответствовать сцене. */
const val QUAD_TTL_MS = 400L

/** Порог «три точки на одной прямой» для проверки выпуклости. */
const val COLLINEAR_EPS = 1e-6f

/**
 * Приводит четыре произвольные точки к порядку TL → TR → BR → BL.
 *
 * Сортируем по углу вокруг центра, а не по сумме/разности координат: последнее
 * врёт на сильно наклонённых листах, а наклон — обычное дело при съёмке с рук.
 * Затем стартовой берём вершину, ближайшую к левому верхнему углу.
 */
fun orderCorners(points: List<QuadPoint>): List<QuadPoint>? {
    if (points.size != 4) return null
    val cx = points.map { it.x }.average().toFloat()
    val cy = points.map { it.y }.average().toFloat()
    // Ось Y направлена вниз, поэтому рост угла = обход по часовой стрелке.
    val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
    val startIndex = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: return null
    return List(4) { clockwise[(startIndex + it) % 4] }
}

/**
 * Переводит точку из нормализованных координат неповёрнутого кадра в
 * нормализованные координаты ориентированного изображения.
 *
 * [rotationDegrees] — это `imageInfo.rotationDegrees`: на сколько кадр надо
 * повернуть по часовой стрелке, чтобы он встал «как видит человек».
 */
fun rotateNormalized(point: QuadPoint, rotationDegrees: Int): QuadPoint =
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> QuadPoint(1f - point.y, point.x)
        180 -> QuadPoint(1f - point.x, 1f - point.y)
        270 -> QuadPoint(point.y, 1f - point.x)
        else -> point
    }

/**
 * Главный преобразователь: crop-local неповёрнутые пиксели → [NormalizedQuad].
 *
 * [points] — точки внутри `cropRect` (координаты отсчитываются от его левого
 * верхнего угла), [cropWidth]/[cropHeight] — размеры самого `cropRect`.
 * Работаем только по нему: полный буфер в портрете содержит полосы слева и
 * справа, которых оператор не видит, и контур оттуда стал бы рамкой-призраком.
 */
fun buildNormalizedQuad(
    points: List<QuadPoint>,
    cropWidth: Int,
    cropHeight: Int,
    rotationDegrees: Int,
): NormalizedQuad? {
    if (cropWidth <= 0 || cropHeight <= 0) return null
    val ordered = orderCorners(points) ?: return null
    val oriented = ordered
        .map { QuadPoint(it.x / cropWidth, it.y / cropHeight) }
        .map { rotateNormalized(it, rotationDegrees) }
    // После поворота прежний «левый верхний» мог перестать им быть.
    val reordered = orderCorners(oriented) ?: return null
    return NormalizedQuad(reordered[0], reordered[1], reordered[2], reordered[3])
}

/**
 * Отбраковка кандидата. Возвращает `null`, если это не похоже на документ.
 *
 * Отдельно ловим случай «контур совпал с границей кадра»: Canny охотно находит
 * рамку самого изображения, и без этой проверки сканер бодро предлагал бы
 * обрезать снимок ровно по его же краям.
 */
fun validateQuad(quad: NormalizedQuad): NormalizedQuad? {
    if (!isConvex(quad.points)) return null

    val area = quad.area
    if (area < MIN_QUAD_AREA || area > MAX_QUAD_AREA) return null

    val sides = quad.points.indices.map { i ->
        val a = quad.points[i]
        val b = quad.points[(i + 1) % 4]
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }
    if (sides.any { it < MIN_QUAD_SIDE }) return null

    if (looksLikeFrameBorder(quad)) return null

    return quad
}

/** Все четыре угла прижаты к краям кадра → это граница кадра, а не лист. */
fun looksLikeFrameBorder(quad: NormalizedQuad): Boolean = quad.points.all { p ->
    val nearVertical = p.x <= FRAME_EDGE_EPS || p.x >= 1f - FRAME_EDGE_EPS
    val nearHorizontal = p.y <= FRAME_EDGE_EPS || p.y >= 1f - FRAME_EDGE_EPS
    nearVertical && nearHorizontal
}

/**
 * Строгая выпуклость: все векторные произведения соседних рёбер одного знака
 * и ни одно не равно нулю.
 *
 * Нулевое произведение означает три точки на одной прямой — approxPolyDP такое
 * выдаёт, и формально это «четырёхугольник», а по сути вырожденный треугольник.
 * Выпрямлять по нему нельзя: warp получит вырожденную матрицу и испортит кадр.
 */
fun isConvex(points: List<QuadPoint>): Boolean {
    if (points.size != 4) return false
    var negative = false
    var positive = false
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        val c = points[(i + 2) % points.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (abs(cross) <= COLLINEAR_EPS) return false
        if (cross < 0) negative = true else positive = true
        if (negative && positive) return false
    }
    return true
}

/** Из валидных кандидатов берём самый крупный — документ обычно главный объект. */
fun selectLargestQuad(candidates: List<NormalizedQuad>): NormalizedQuad? =
    candidates.mapNotNull { validateQuad(it) }.maxByOrNull { it.area }

/** Максимальное смещение соответствующих углов — мера «тот же это квад или нет». */
fun maxCornerShift(a: NormalizedQuad, b: NormalizedQuad): Float =
    a.points.zip(b.points).maxOf { (p, q) ->
        max(abs(p.x - q.x), abs(p.y - q.y))
    }

private fun polygonArea(points: List<QuadPoint>): Float {
    var sum = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return abs(sum) / 2f
}

/**
 * Гасит дрожание рамки: показываем её только после [requiredStreak] подряд
 * согласованных кадров. Без этого рамка прыгает на каждом шуме и целиться
 * невозможно.
 *
 * Класс чистый и не знает про время системы — [nowMs] всегда приходит снаружи,
 * поэтому TTL проверяется в тестах без всяких часов.
 */
class QuadStabilizer(
    private val requiredStreak: Int = STABLE_STREAK,
    private val maxShift: Float = MAX_CORNER_SHIFT,
) {
    private var candidate: NormalizedQuad? = null
    private var streak = 0
    private var stable: StableQuad? = null

    /** Скармливает результат очередного кадра. Возвращает текущую рамку для показа. */
    fun onFrame(quad: NormalizedQuad?, nowMs: Long): NormalizedQuad? {
        if (quad == null) {
            candidate = null
            streak = 0
            stable = null
            return null
        }
        val previous = candidate
        streak = if (previous != null && maxCornerShift(previous, quad) <= maxShift) streak + 1 else 1
        candidate = quad
        if (streak >= requiredStreak) {
            stable = StableQuad(quad, nowMs)
        }
        return stable?.quad
    }

    /**
     * Снимок для затвора. Отдаёт рамку, только если она **свежая**: между
     * нажатием и сохранением JPEG проходит заметное время, и обрезать по
     * устаревшему кваду — верный способ отрезать половину УПД.
     */
    fun snapshotIfFresh(nowMs: Long, ttlMs: Long = QUAD_TTL_MS): NormalizedQuad? =
        stable?.takeIf { nowMs - it.detectedAt <= ttlMs }?.quad

    /** Сброс при повороте, fallback-биндинге и остановке анализатора. */
    fun reset() {
        candidate = null
        streak = 0
        stable = null
    }
}

/**
 * Размер результата выпрямления: берём средние длины противоположных сторон,
 * чтобы не растягивать документ, и ограничиваем сверху — downstream всё равно
 * ужимает до 2048, а лишние пиксели дали бы всплеск памяти на warp.
 */
fun warpTargetSize(quad: NormalizedQuad, sourceWidth: Int, sourceHeight: Int, maxSide: Int): Pair<Int, Int> {
    val px = quad.scaleTo(sourceWidth, sourceHeight)
    fun dist(a: QuadPoint, b: QuadPoint) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    val width = (dist(px[0], px[1]) + dist(px[3], px[2])) / 2f
    val height = (dist(px[0], px[3]) + dist(px[1], px[2])) / 2f
    val w = max(1, width.toInt())
    val h = max(1, height.toInt())
    val longest = max(w, h)
    if (longest <= maxSide) return w to h
    val scale = maxSide.toFloat() / longest
    return max(1, (w * scale).toInt()) to max(1, (h * scale).toInt())
}
