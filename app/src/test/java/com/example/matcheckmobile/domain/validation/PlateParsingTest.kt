package com.example.matcheckmobile.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор госномера, распознанного с фото машины (PlateParsing.kt).
 *
 * Номера синтетические: настоящие из боевой БД в репозиторий не кладём.
 *
 * Две регрессии, ради которых написана бо́льшая часть тестов:
 *
 * 1. Один и тот же номер приезжает из ML Kit несколькими путями сразу (слово,
 *    склейка слов, строка, блок). Без группировки кандидатов по распознанному
 *    номеру ДО проверки неоднозначности порог «в 1,3 раза крупнее» сравнил бы
 *    два вхождения одного номера и отверг бы правильный результат.
 * 2. В бой уехал `М583МУ792` вместо `М583МУ799`. Склейка соседних по списку
 *    элементов без проверки расстояния способна приварить к номеру постороннюю
 *    цифру с борта машины.
 */
class PlateParsingTest {

    // --- фикстуры -----------------------------------------------------------

    /** Слово с рамкой: x — левый край, все слова одной высоты на одной строке. */
    private fun word(text: String, x: Int, width: Int, y: Int = 100, height: Int = 40) =
        OcrElement(text, OcrRect(x, y, x + width, y + height))

    private fun lineOf(vararg words: OcrElement): OcrLine {
        val bounds = words.map { it.bounds }.reduce(OcrRect::union)
        return OcrLine(words.joinToString(" ") { it.text }, bounds, words.toList())
    }

    private fun blockOf(vararg lines: OcrLine) = listOf(OcrBlock(lines.toList()))

    private fun plateOf(blocks: List<OcrBlock>): String? =
        pickPlate(buildCandidates(blocks))?.canonical

    // --- нормализация и починка ---------------------------------------------

    @Test
    fun `латиница с фото приводится к кириллице`() {
        assertEquals("О123ВС77", canonicalisePlate("O123BC77"))
    }

    @Test
    fun `ноль на буквенной позиции чинится в букву`() {
        assertEquals("О123ВС77", canonicalisePlate("0123BC77"))
    }

    @Test
    fun `буква на цифровой позиции чинится в цифру`() {
        // O в регионе — это ноль, но та же O в серии остаётся буквой.
        assertEquals("О123ВС70", canonicalisePlate("O123BC7O"))
    }

    @Test
    fun `хвост RUS и пробелы срезаются`() {
        assertEquals("А777АА99", canonicalisePlate("A 777 AA 99 RUS"))
    }

    @Test
    fun `мусор не превращается в номер`() {
        assertNull("рекламная надпись", canonicalisePlate("ГРУЗОПЕРЕВОЗКИ"))
        assertNull("телефон на борту", canonicalisePlate("8 800 555 35 35"))
        assertNull("слишком коротко", canonicalisePlate("A123BC"))
        assertNull("пусто", canonicalisePlate(""))
    }

    // --- геометрическая связность -------------------------------------------

    @Test
    fun `далёкая цифра не приваривается к номеру`() {
        // Регресс на М583МУ792: «79» — конец номера, «2» — посторонняя надпись
        // на другом конце борта. Склеивать их нельзя ни окном слов, ни строкой.
        val blocks = blockOf(
            lineOf(word("A123BC79", x = 100, width = 180), word("2", x = 900, width = 20)),
        )
        val plate = plateOf(blocks)
        assertNotEquals("приваренная двойка", "А123ВС792", plate)
        assertEquals("А123ВС79", plate)
    }

    @Test
    fun `номер и регион соседними словами собираются вместе`() {
        // Зазор 20 px при высоте символа 40 — это одно слово, разорванное ML Kit.
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140), word("77", x = 260, width = 40)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `номер и регион в разных строках одного блока собираются вместе`() {
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140, y = 100, height = 40)),
            lineOf(word("77", x = 110, width = 40, y = 145, height = 35)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `строки из разных концов кадра не склеиваются`() {
        // Вертикальный зазор мал, но по горизонтали рамки не пересекаются —
        // это две разные надписи, а не двухстрочная табличка.
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140, y = 100, height = 40)),
            lineOf(word("77", x = 900, width = 40, y = 145, height = 35)),
        )
        assertNull(plateOf(blocks))
    }

    @Test
    fun `рамка склеенного кандидата объединяет составляющие`() {
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140), word("77", x = 260, width = 40)),
        )
        val selected = pickPlate(buildCandidates(blocks))
        requireNotNull(selected)
        assertEquals(100, selected.bounds.left)
        assertEquals(300, selected.bounds.right)
        assertTrue("рамка обязана покрывать обе части", selected.bounds.width >= 200)
    }

    // --- дедупликация и неоднозначность -------------------------------------

    @Test
    fun `один номер, найденный и строкой и словом, не считается неоднозначностью`() {
        val blocks = blockOf(lineOf(word("A123BC77", x = 100, width = 200)))
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `два разных номера близкого размера — не подставляем ничего`() {
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 100, width = 200, y = 100, height = 40)),
            lineOf(word("X456YA199", x = 100, width = 220, y = 400, height = 38)),
        )
        assertNull(plateOf(blocks))
    }

    @Test
    fun `заметно более крупный номер выигрывает у мелкого`() {
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 100, width = 300, y = 100, height = 60)),
            lineOf(word("X456YA199", x = 100, width = 120, y = 400, height = 20)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `нулевой вес не выигрывает у другого номера`() {
        // Высота 0 — это «рамки не было», а не «текст крошечный».
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 0, width = 0, y = 0, height = 0)),
            lineOf(word("X456YA199", x = 0, width = 0, y = 0, height = 0)),
        )
        assertNull(plateOf(blocks))
    }

    @Test
    fun `текст без номеров не даёт результата`() {
        val blocks = blockOf(lineOf(word("ГРУЗОПЕРЕВОЗКИ", x = 100, width = 400, height = 50)))
        assertNull(plateOf(blocks))
    }

    // --- геометрия кропа для второго прохода --------------------------------

    @Test
    fun `рамка пересчитывается в координаты оригинала и расширяется`() {
        // Уменьшенный кадр вдвое: рамка обязана удвоиться, плюс поля 20 %.
        val crop = cropRect(
            bounds = OcrRect(100, 100, 200, 140),
            decodedWidth = 1000, decodedHeight = 750,
            originalWidth = 2000, originalHeight = 1500,
        )
        requireNotNull(crop)
        assertTrue("левый край ушёл влево на поле", crop.left < 200)
        assertTrue("правый край ушёл вправо на поле", crop.right > 400)
        assertTrue(crop.width > 200)
    }

    @Test
    fun `кроп обрезается по границам оригинала`() {
        // Рамка у самого края: BitmapRegionDecoder бросает на выходе за границы.
        val crop = cropRect(
            bounds = OcrRect(0, 0, 1000, 750),
            decodedWidth = 1000, decodedHeight = 750,
            originalWidth = 1000, originalHeight = 750,
        )
        requireNotNull(crop)
        assertTrue(crop.left >= 0)
        assertTrue(crop.top >= 0)
        assertTrue(crop.right <= 1000)
        assertTrue(crop.bottom <= 750)
    }

    @Test
    fun `непропорциональное уменьшение считается раздельно по осям`() {
        val crop = cropRect(
            bounds = OcrRect(10, 10, 20, 20),
            decodedWidth = 100, decodedHeight = 100,
            originalWidth = 1000, originalHeight = 200,
            expandRatio = 0.0,
        )
        requireNotNull(crop)
        // scaleX = 10, scaleY = 2 — оси не должны перепутаться.
        assertEquals(100, crop.left)
        assertEquals(20, crop.top)
        assertEquals(200, crop.right)
        assertEquals(40, crop.bottom)
    }

    @Test
    fun `битые габариты не роняют кроп`() {
        assertNull(cropRect(OcrRect(0, 0, 10, 10), 0, 0, 100, 100))
        assertNull(cropRect(OcrRect(0, 0, 10, 10), 100, 100, 0, 0))
    }

    // --- арбитраж с ручным вводом -------------------------------------------

    @Test
    fun `пустое нетронутое поле заполняется`() {
        assertEquals("А123ВС77", plateAfterOcr(current = "", editedByUser = false, recognised = "А123ВС77"))
    }

    @Test
    fun `набранный руками номер не затирается`() {
        assertNull(plateAfterOcr(current = "Х456УА199", editedByUser = true, recognised = "А123ВС77"))
    }

    @Test
    fun `очищенное вручную поле остаётся пустым`() {
        assertNull(plateAfterOcr(current = "", editedByUser = true, recognised = "А123ВС77"))
    }

    @Test
    fun `второй результат не переписывает первый`() {
        assertNull(plateAfterOcr(current = "А123ВС77", editedByUser = false, recognised = "Х456УА199"))
    }
}
