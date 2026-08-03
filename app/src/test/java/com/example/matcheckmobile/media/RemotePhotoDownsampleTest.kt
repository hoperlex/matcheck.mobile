package com.example.matcheckmobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [chooseSampleSize] — выбор степени двойки при декодировании фото перед
 * отправкой на портал.
 *
 * Регрессия, ради которой тест написан: раньше сэмплировали, пока сторона не
 * станет ≤ 2048, и на портал уходило вдвое-вчетверо меньше лимита (4160 → 1040).
 * Здесь проверяется только выбор степени двойки; итоговый размер и отсутствие
 * апскейла живут в `scaleToMaxSide` и покрыты инструментальным
 * `RemotePhotoStorageTest`.
 */
class RemotePhotoDownsampleTest {

    private val maxSide = 2048

    private fun sample(longest: Int) = chooseSampleSize(longest, longest * 3 / 4, maxSide)

    @Test
    fun `типовые матрицы планшетов декодируются не мельче лимита`() {
        assertEquals("13 Мп: было 1040 на портале", 2, sample(4160))
        assertEquals("12 Мп: сэмплировать нечего", 1, sample(4000))
        assertEquals("16 Мп: было 1152 на портале", 2, sample(4608))
        assertEquals("8 Мп", 1, sample(3264))
        assertEquals("ровно лимит", 1, sample(2048))
        assertEquals("мельче лимита — не трогаем", 1, sample(1600))
        assertEquals("совсем мелкий кадр", 1, sample(800))
    }

    @Test
    fun `границы степеней двойки`() {
        assertEquals(1, sample(4095))
        assertEquals(2, sample(4096))
        assertEquals(2, sample(4097))
        assertEquals(2, sample(8191))
        assertEquals(4, sample(8192))
    }

    @Test
    fun `решает длинная сторона, а не ширина`() {
        assertEquals("портрет 3120x4160", 2, chooseSampleSize(3120, 4160, maxSide))
        assertEquals("ландшафт 4160x3120", 2, chooseSampleSize(4160, 3120, maxSide))
        assertEquals("панорама 6000x1000", 2, chooseSampleSize(6000, 1000, maxSide))
    }

    // Инвариант правки: после сэмплирования длинная сторона всегда в
    // [maxSide, 2 * maxSide) — не мельче лимита (иначе потеря качества) и не
    // крупнее вдвое (иначе лишняя память под декод).
    @Test
    fun `декодированная сторона остаётся в границах от лимита до двух лимитов`() {
        for (longest in 2048..9000) {
            val decoded = longest / sample(longest)
            assertTrue(
                "$longest px → декод $decoded px мельче лимита",
                decoded >= maxSide,
            )
            assertTrue(
                "$longest px → декод $decoded px больше двух лимитов",
                decoded < 2 * maxSide,
            )
        }
    }

    // Битый заголовок не должен ронять подготовку фото: BitmapFactory при
    // неудачном inJustDecodeBounds отдаёт -1, и это штатный путь.
    @Test
    fun `некорректные габариты и лимит дают единицу вместо падения`() {
        assertEquals("bounds не прочитались", 1, chooseSampleSize(-1, -1, maxSide))
        assertEquals(1, chooseSampleSize(0, 0, maxSide))
        assertEquals(1, chooseSampleSize(4160, 0, maxSide))
        assertEquals(1, chooseSampleSize(4160, 3120, 0))
        assertEquals(1, chooseSampleSize(4160, 3120, -2048))
    }

    // Предельные Int'ы: умножение sampleSize * 2 идёт в Long, переполнения нет,
    // цикл завершается.
    @Test
    fun `предельные габариты не переполняют счётчик`() {
        assertTrue(chooseSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, maxSide) > 0)
        assertTrue(chooseSampleSize(Int.MAX_VALUE, 1, 1) > 0)
        assertEquals(1, chooseSampleSize(Int.MAX_VALUE, 1, Int.MAX_VALUE))
    }
}
