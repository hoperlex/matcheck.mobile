package com.example.matcheckmobile.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор госномера, распознанного с фото машины (PlateParsing.kt).
 *
 * Номера здесь синтетические: настоящие из боевой БД в репозиторий не кладём.
 *
 * Главная регрессия, ради которой написана половина тестов: один и тот же номер
 * приезжает из ML Kit несколькими путями сразу (слово, склейка слов, строка, блок).
 * Если не сгруппировать кандидатов по распознанному номеру ДО проверки
 * неоднозначности, порог «в 1,3 раза крупнее» сравнил бы два вхождения одного и того
 * же номера и отверг бы правильный результат почти всегда.
 */
class PlateParsingTest {

    private fun line(text: String, height: Int, vararg words: Pair<String, Int>) =
        OcrLine(text, height, words.map { OcrElement(it.first, it.second) })

    private fun blockOf(vararg lines: OcrLine) = listOf(OcrBlock(lines.toList()))

    // --- нормализация и починка -------------------------------------------------

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
    fun `кириллица с фото остаётся кириллицей`() {
        assertEquals("Х456УА199", canonicalisePlate("Х456УА199"))
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

    // --- сборка кандидатов ------------------------------------------------------

    @Test
    fun `номер и регион в разных строках одного блока собираются вместе`() {
        // Двухстрочная табличка: сверху серия, снизу регион с кодом страны.
        val blocks = blockOf(
            line("A123BC", 40, "A123BC" to 40),
            line("77 RUS", 30, "77" to 30, "RUS" to 28),
        )
        assertEquals("А123ВС77", pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `номер и регион разными словами одной строки собираются вместе`() {
        val blocks = blockOf(line("A123BC 77", 40, "A123BC" to 40, "77" to 38))
        assertEquals("А123ВС77", pickPlate(buildCandidates(blocks)))
    }

    // --- дедупликация и неоднозначность ----------------------------------------

    @Test
    fun `один номер, найденный и строкой и словом, не считается неоднозначностью`() {
        // Ровно тот случай, который ломала бы проверка 1,3× без группировки:
        // строка и слово дают одинаковый номер с одинаковым весом.
        val blocks = blockOf(line("A123BC77", 40, "A123BC77" to 40))
        assertEquals("А123ВС77", pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `два разных номера близкого размера — не подставляем ничего`() {
        // Кадр, где видно две машины: угадывать нельзя.
        val blocks = blockOf(
            line("A123BC77", 40, "A123BC77" to 40),
            line("X456YA199", 38, "X456YA199" to 38),
        )
        assertNull(pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `заметно более крупный номер выигрывает у мелкого`() {
        val blocks = blockOf(
            line("A123BC77", 60, "A123BC77" to 60),
            line("X456YA199", 20, "X456YA199" to 20),
        )
        assertEquals("А123ВС77", pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `нулевой вес не выигрывает у другого номера`() {
        // Высота 0 — это «рамки не было», а не «текст крошечный»: такой кандидат
        // не имеет права решать спор двух разных номеров.
        val blocks = blockOf(
            line("A123BC77", 0, "A123BC77" to 0),
            line("X456YA199", 0, "X456YA199" to 0),
        )
        assertNull(pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `единственный номер без рамок всё равно принимается`() {
        val blocks = blockOf(line("A123BC77", 0, "A123BC77" to 0))
        assertEquals("А123ВС77", pickPlate(buildCandidates(blocks)))
    }

    @Test
    fun `текст без номеров не даёт результата`() {
        val blocks = blockOf(line("ГРУЗОПЕРЕВОЗКИ", 50, "ГРУЗОПЕРЕВОЗКИ" to 50))
        assertNull(pickPlate(buildCandidates(blocks)))
    }

    // --- арбитраж с ручным вводом ----------------------------------------------

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
        // Инспектор стёр номер намеренно — следующее фото не должно вписать его обратно.
        assertNull(plateAfterOcr(current = "", editedByUser = true, recognised = "А123ВС77"))
    }

    @Test
    fun `второй результат не переписывает первый`() {
        // После первой подстановки поле непустое, даже если инспектор его не трогал.
        assertNull(plateAfterOcr(current = "А123ВС77", editedByUser = false, recognised = "Х456УА199"))
    }
}
