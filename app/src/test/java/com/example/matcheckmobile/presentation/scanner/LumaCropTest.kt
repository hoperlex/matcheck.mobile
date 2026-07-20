package com.example.matcheckmobile.presentation.scanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты вырезания Y-плоскости. Именно здесь ошибка не падает, а тихо портит
 * картинку («лесенка»), поэтому rowStride и cropRect проверяем явно.
 */
class LumaCropTest {

    /** Буфер, где значение пикселя = row*10 + col, плюс мусор в паддинге. */
    private fun plane(width: Int, height: Int, rowStride: Int): ByteArray {
        val buf = ByteArray(rowStride * height) { -1 } // -1 = «мусор» в хвосте строки
        for (r in 0 until height) {
            for (c in 0 until width) {
                buf[r * rowStride + c] = (r * 10 + c).toByte()
            }
        }
        return buf
    }

    @Test
    fun `crop respects rowStride padding`() {
        // Ширина 4, но строка занимает 8 байт — хвост не должен попасть в результат.
        val src = plane(width = 4, height = 3, rowStride = 8)

        val out = cropLuma(src, rowStride = 8, pixelStride = 1, 0, 0, 4, 3)!!

        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13, 20, 21, 22, 23),
            out,
        )
    }

    @Test
    fun `crop applies offset`() {
        val src = plane(width = 6, height = 4, rowStride = 6)

        val out = cropLuma(src, rowStride = 6, pixelStride = 1, cropLeft = 2, cropTop = 1, cropWidth = 3, cropHeight = 2)!!

        // Ожидаем строки 1..2, колонки 2..4
        assertArrayEquals(byteArrayOf(12, 13, 14, 22, 23, 24), out)
    }

    @Test
    fun `crop honours pixelStride`() {
        // pixelStride=2 — пиксели через один (встречается на некоторых устройствах).
        val src = ByteArray(16) { it.toByte() }

        val out = cropLuma(src, rowStride = 8, pixelStride = 2, 0, 0, 4, 2)!!

        assertArrayEquals(byteArrayOf(0, 2, 4, 6, 8, 10, 12, 14), out)
    }

    @Test
    fun `crop output size matches requested rect`() {
        val src = plane(width = 640, height = 480, rowStride = 704)

        val out = cropLuma(src, rowStride = 704, pixelStride = 1, 80, 60, 480, 360)!!

        assertEquals(480 * 360, out.size)
    }

    @Test
    fun `crop rejects invalid geometry`() {
        val src = plane(width = 4, height = 4, rowStride = 4)

        assertNull(cropLuma(src, 4, 1, 0, 0, 0, 4))
        assertNull(cropLuma(src, 4, 1, 0, 0, 4, 0))
        assertNull(cropLuma(src, 0, 1, 0, 0, 4, 4))
        assertNull(cropLuma(src, 4, 0, 0, 0, 4, 4))
        assertNull(cropLuma(src, 4, 1, -1, 0, 4, 4))
    }

    @Test
    fun `crop rejects buffer that is too short`() {
        // Лучше отказаться, чем отдать половину кадра и «найти» на ней документ.
        val truncated = ByteArray(10)

        assertNull(cropLuma(truncated, rowStride = 8, pixelStride = 1, 0, 0, 8, 4))
    }
}
