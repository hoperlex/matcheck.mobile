package com.example.matcheckmobile.presentation.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты чистой геометрии рамки. Проверяют ровно то, что ломается молча:
 * порядок углов, повороты, работу по cropRect и свежесть снимка для затвора.
 */
class DocumentQuadTest {

    private fun p(x: Float, y: Float) = QuadPoint(x, y)

    private fun quad(
        tl: QuadPoint, tr: QuadPoint, br: QuadPoint, bl: QuadPoint,
    ) = NormalizedQuad(tl, tr, br, bl)

    /** Прямоугольник с отступом [inset] от краёв единичного квадрата. */
    private fun inset(inset: Float) = quad(
        p(inset, inset), p(1f - inset, inset), p(1f - inset, 1f - inset), p(inset, 1f - inset),
    )

    // ── Порядок углов ────────────────────────────────────────────────────

    @Test
    fun `orderCorners returns TL TR BR BL from any input order`() {
        val tl = p(10f, 10f)
        val tr = p(90f, 12f)
        val br = p(92f, 80f)
        val bl = p(8f, 78f)

        listOf(
            listOf(tl, tr, br, bl),
            listOf(br, bl, tl, tr),
            listOf(bl, br, tr, tl),
            listOf(tr, br, bl, tl),
        ).forEach { input ->
            val ordered = orderCorners(input)
            assertEquals("вход: $input", listOf(tl, tr, br, bl), ordered)
        }
    }

    @Test
    fun `orderCorners rejects wrong point count`() {
        assertNull(orderCorners(emptyList()))
        assertNull(orderCorners(listOf(p(0f, 0f), p(1f, 0f), p(1f, 1f))))
    }

    @Test
    fun `orderCorners handles tilted document`() {
        // Наклон — обычное дело при съёмке с рук; сортировка по сумме координат
        // здесь бы соврала, поэтому случай проверяем отдельно.
        val ordered = orderCorners(listOf(p(50f, 5f), p(95f, 55f), p(45f, 95f), p(5f, 45f)))!!
        assertEquals(p(5f, 45f), ordered[0])
        assertTrue(isConvex(ordered))
    }

    // ── Повороты ─────────────────────────────────────────────────────────

    @Test
    fun `rotateNormalized maps corners for 0 90 180 270`() {
        val topLeft = p(0f, 0f)
        assertEquals(p(0f, 0f), rotateNormalized(topLeft, 0))
        assertEquals(p(1f, 0f), rotateNormalized(topLeft, 90))
        assertEquals(p(1f, 1f), rotateNormalized(topLeft, 180))
        assertEquals(p(0f, 1f), rotateNormalized(topLeft, 270))
    }

    @Test
    fun `rotateNormalized normalizes negative and oversized angles`() {
        val point = p(0.25f, 0.75f)
        assertEquals(rotateNormalized(point, 90), rotateNormalized(point, 450))
        assertEquals(rotateNormalized(point, 270), rotateNormalized(point, -90))
    }

    @Test
    fun `buildNormalizedQuad keeps document under every rotation`() {
        // Один и тот же лист в кадре 640x480 при всех четырёх ориентациях
        // обязан остаться выпуклым, корректно упорядоченным и той же площади.
        val points = listOf(p(64f, 48f), p(576f, 48f), p(576f, 432f), p(64f, 432f))
        val areas = listOf(0, 90, 180, 270).map { rotation ->
            val q = buildNormalizedQuad(points, 640, 480, rotation)
            assertNotNull("поворот $rotation", q)
            assertTrue("поворот $rotation: выпуклость", isConvex(q!!.points))
            assertEquals("поворот $rotation: TL первым", q.topLeft, q.points.first())
            q.area
        }
        areas.forEach { assertEquals(areas.first(), it, 0.001f) }
    }

    @Test
    fun `buildNormalizedQuad maps four cropRect corners`() {
        // Углы cropRect при повороте 90 должны лечь ровно в углы единичного
        // квадрата — если здесь ошибка, рамка «поедет» именно в альбоме.
        val corners = listOf(p(0f, 0f), p(640f, 0f), p(640f, 480f), p(0f, 480f))
        val q = buildNormalizedQuad(corners, 640, 480, 90)!!
        val xs = q.points.map { it.x }.sorted()
        val ys = q.points.map { it.y }.sorted()
        assertEquals(0f, xs.first(), 0.001f)
        assertEquals(1f, xs.last(), 0.001f)
        assertEquals(0f, ys.first(), 0.001f)
        assertEquals(1f, ys.last(), 0.001f)
    }

    @Test
    fun `buildNormalizedQuad rejects empty crop`() {
        val points = listOf(p(0f, 0f), p(1f, 0f), p(1f, 1f), p(0f, 1f))
        assertNull(buildNormalizedQuad(points, 0, 480, 0))
        assertNull(buildNormalizedQuad(points, 640, 0, 0))
    }

    // ── Отбраковка ───────────────────────────────────────────────────────

    @Test
    fun `validateQuad accepts a normal document`() {
        assertNotNull(validateQuad(inset(0.15f)))
    }

    @Test
    fun `validateQuad rejects quad matching the frame border`() {
        // Canny охотно находит границу самого кадра; без этой отбраковки
        // сканер предложил бы «обрезать» снимок по его же краям.
        val border = quad(p(0f, 0f), p(1f, 0f), p(1f, 1f), p(0f, 1f))
        assertTrue(looksLikeFrameBorder(border))
        assertNull(validateQuad(border))
    }

    @Test
    fun `validateQuad rejects too small and too large`() {
        assertNull(validateQuad(inset(0.47f)))
        assertNull(validateQuad(quad(p(0.001f, 0.001f), p(0.999f, 0.001f), p(0.999f, 0.999f), p(0.001f, 0.999f))))
    }

    @Test
    fun `validateQuad rejects non convex`() {
        // Вогнутый «дротик»: третья вершина провалена внутрь.
        val dart = quad(p(0f, 0f), p(1f, 0f), p(0.2f, 0.2f), p(0f, 1f))
        assertNull(validateQuad(dart))
    }

    @Test
    fun `validateQuad rejects degenerate triangle with collinear points`() {
        // approxPolyDP умеет отдать «четырёхугольник», у которого три точки на
        // одной прямой. Формально их четыре, по сути это треугольник, и warp по
        // нему получил бы вырожденную матрицу.
        val collinear = quad(p(0f, 0f), p(1f, 0f), p(0.5f, 0.5f), p(0f, 1f))
        assertTrue(!isConvex(collinear.points))
        assertNull(validateQuad(collinear))
    }

    @Test
    fun `validateQuad rejects degenerate sliver`() {
        val sliver = quad(p(0.1f, 0.1f), p(0.9f, 0.1f), p(0.9f, 0.12f), p(0.1f, 0.12f))
        assertNull(validateQuad(sliver))
    }

    @Test
    fun `selectLargestQuad picks the biggest valid candidate`() {
        val small = inset(0.35f)
        val big = inset(0.10f)
        val invalid = quad(p(0f, 0f), p(1f, 0f), p(1f, 1f), p(0f, 1f)) // граница кадра

        val chosen = selectLargestQuad(listOf(small, invalid, big))

        assertEquals(big, chosen)
    }

    @Test
    fun `selectLargestQuad returns null when nothing valid`() {
        assertNull(selectLargestQuad(emptyList()))
        assertNull(selectLargestQuad(listOf(inset(0.47f))))
    }

    // ── Стабилизация и TTL ───────────────────────────────────────────────

    @Test
    fun `stabilizer publishes only after a consistent streak`() {
        val stabilizer = QuadStabilizer(requiredStreak = 3)
        val q = inset(0.2f)

        assertNull(stabilizer.onFrame(q, 0L))
        assertNull(stabilizer.onFrame(q, 10L))
        assertNotNull(stabilizer.onFrame(q, 20L))
    }

    @Test
    fun `stabilizer restarts streak when quad jumps`() {
        val stabilizer = QuadStabilizer(requiredStreak = 3)
        stabilizer.onFrame(inset(0.2f), 0L)
        stabilizer.onFrame(inset(0.2f), 10L)
        // Резкий скачок — считаем это новым кандидатом, а не продолжением.
        assertNull(stabilizer.onFrame(inset(0.4f), 20L))
    }

    @Test
    fun `stabilizer drops frame when document disappears`() {
        val stabilizer = QuadStabilizer(requiredStreak = 2)
        val q = inset(0.2f)
        stabilizer.onFrame(q, 0L)
        assertNotNull(stabilizer.onFrame(q, 10L))

        assertNull(stabilizer.onFrame(null, 20L))
        assertNull(stabilizer.snapshotIfFresh(21L))
    }

    @Test
    fun `snapshot is fresh within ttl and stale after`() {
        val stabilizer = QuadStabilizer(requiredStreak = 1)
        stabilizer.onFrame(inset(0.2f), 1_000L)

        assertNotNull(stabilizer.snapshotIfFresh(1_000L + QUAD_TTL_MS, QUAD_TTL_MS))
        assertNull(stabilizer.snapshotIfFresh(1_000L + QUAD_TTL_MS + 1, QUAD_TTL_MS))
    }

    @Test
    fun `reset clears published quad`() {
        val stabilizer = QuadStabilizer(requiredStreak = 1)
        stabilizer.onFrame(inset(0.2f), 0L)
        assertNotNull(stabilizer.snapshotIfFresh(0L))

        stabilizer.reset()

        assertNull(stabilizer.snapshotIfFresh(0L))
    }

    // ── Масштабирование и размер результата ──────────────────────────────

    @Test
    fun `scaleTo expands normalized quad to pixels`() {
        val scaled = inset(0.25f).scaleTo(800, 400)
        assertEquals(200f, scaled[0].x, 0.001f)
        assertEquals(100f, scaled[0].y, 0.001f)
        assertEquals(600f, scaled[2].x, 0.001f)
        assertEquals(300f, scaled[2].y, 0.001f)
    }

    @Test
    fun `warpTargetSize caps the long side`() {
        val (w, h) = warpTargetSize(inset(0f), 4000, 3000, maxSide = 2048)
        assertEquals(2048, w)
        assertEquals(1536, h)
    }

    @Test
    fun `warpTargetSize keeps small results untouched`() {
        val (w, h) = warpTargetSize(inset(0f), 1000, 800, maxSide = 2048)
        assertEquals(1000, w)
        assertEquals(800, h)
    }
}
