package com.example.matcheckmobile.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строки в кейсах — реальные написания из боевой БД (`suppliers`), кроме
 * помеченных как страховочные: NBSP и пунктирные ОПФ в данных пока не
 * встречаются, но приезжают из распознавания легко.
 */
class PartyGroupingTest {

    // --- Склейка написаний одного поставщика ---

    @Test
    fun `три Альбия с разными написаниями склеиваются в одну группу`() {
        val rows = listOf(
            """ООО ТД "АЛЬБИЯ"""",
            """ООО ТД "Альбия"""",
            """Общество с ограниченной ответственностью Торговый Дом "Альбия"""",
        )

        val groups = groupByParty(rows) { it }

        assertEquals(1, groups.size)
        assertEquals(3, groups.first().rows.size)
    }

    @Test
    fun `висячий и двойной пробелы не создают отдельных групп`() {
        assertEquals(partyGroupKey("ООО «АЛЮПРОМ»"), partyGroupKey("ООО «АЛЮПРОМ» "))
        assertEquals(partyGroupKey("""ООО "ЛЕНИНЕРТ""""), partyGroupKey("""ООО  "ЛЕНИНЕРТ""""))
    }

    @Test
    fun `несбалансированные кавычки дают тот же ключ, что и без кавычек`() {
        assertEquals(
            partyGroupKey("АО ТД ЭЛЕКТРОТЕХМОНТАЖ"),
            partyGroupKey("""АО "ТД "ЭЛЕКТРОТЕХМОНТАЖ""""),
        )
    }

    @Test
    fun `полная форма ОПФ равна аббревиатуре, дефис сохраняется`() {
        val full = partyGroupKey("""Общество с ограниченной ответственностью "Фаворит-Электро"""")
        val short = partyGroupKey("""ООО "Фаворит-Электро"""")

        assertEquals(short, full)
        assertTrue("дефис значим в названии", short.contains("фаворит-электро"))
    }

    // --- Организационно-правовые формы ---

    @Test
    fun `длинные формы разворачиваются раньше коротких`() {
        assertEquals("пао ромашка", partyGroupKey("Публичное акционерное общество «Ромашка»"))
        assertEquals("зао ромашка", partyGroupKey("Закрытое акционерное общество «Ромашка»"))
        assertEquals("оао ромашка", partyGroupKey("Открытое акционерное общество «Ромашка»"))
        assertEquals("ао ромашка", partyGroupKey("Акционерное общество «Ромашка»"))
        assertEquals("ооо ромашка", partyGroupKey("Общество с ограниченной ответственностью «Ромашка»"))
        assertEquals("ип иванов", partyGroupKey("Индивидуальный предприниматель Иванов"))
        assertEquals("тд ромашка", partyGroupKey("Торговый дом «Ромашка»"))
    }

    @Test
    fun `разные ОПФ не смешиваются`() {
        assertNotEquals(partyGroupKey("АО «Ромашка»"), partyGroupKey("ЗАО «Ромашка»"))
        assertNotEquals(partyGroupKey("ПАО «Ромашка»"), partyGroupKey("ОАО «Ромашка»"))
    }

    @Test
    fun `пунктирная ОПФ равна обычной — страховочный кейс`() {
        assertEquals(partyGroupKey("ООО Ромашка"), partyGroupKey("О.О.О. Ромашка"))
    }

    @Test
    fun `одиночная буква перед словом не приклеивается`() {
        // Склеиваем только прогоны из двух и более букв, иначе «М Видео»
        // слилось бы в «мвидео» и разошлось с исходным написанием.
        assertEquals("ооо м видео", partyGroupKey("ООО М Видео"))
        assertNotEquals(partyGroupKey("ООО МВидео"), partyGroupKey("ООО М Видео"))
    }

    // --- Символьный мусор ---

    @Test
    fun `ё приводится к е`() {
        assertEquals(partyGroupKey("ООО «Зеленый»"), partyGroupKey("ООО «Зелёный»"))
    }

    @Test
    fun `точки в инициалах не создают отдельных групп`() {
        assertEquals(partyGroupKey("ИП Иванов И. И."), partyGroupKey("ИП Иванов И.И."))
    }

    @Test
    fun `кавычки всех видов эквивалентны`() {
        val expected = partyGroupKey("ООО Ромашка")

        listOf(
            """ООО "Ромашка"""",
            "ООО «Ромашка»",
            "ООО „Ромашка“",
            "ООО “Ромашка”",
            "ООО 'Ромашка'",
            "ООО ‘Ромашка’",
        ).forEach { assertEquals(it, expected, partyGroupKey(it)) }
    }

    @Test
    fun `неразрывные пробелы схлопываются как обычные — страховочный кейс`() {
        val expected = partyGroupKey("ООО Ромашка")

        assertEquals("NBSP", expected, partyGroupKey("ООО Ромашка"))
        assertEquals("узкий NBSP", expected, partyGroupKey("ООО Ромашка"))
        assertEquals("word-joiner", expected, partyGroupKey("ООО⁠ Ромашка"))
    }

    @Test
    fun `разные компании не склеиваются`() {
        val keys = listOf("""ООО "РС"""", """ООО "РК"""", """ООО "РЕГУЛ"""").map(::partyGroupKey)

        assertEquals("все три ключа должны быть различны", 3, keys.toSet().size)
    }

    // --- Группа «не указан» ---

    @Test
    fun `пустые имена уходят в хвостовую группу`() {
        val rows = listOf("ООО «Ромашка»", null, "", "   ")

        val groups = groupByParty(rows) { it }

        val last = groups.last()
        assertEquals(UNKNOWN_PARTY_KEY, last.key)
        assertEquals(UNKNOWN_SUPPLIER_LABEL, last.displayName)
        assertEquals(3, last.rows.size)
    }

    @Test
    fun `хвостовая группа идёт после алфавита, а не по алфавиту`() {
        val rows = listOf("ЯООО «Яблоко»", null, "ААА «Абрикос»")

        val groups = groupByParty(rows) { it }

        assertEquals(listOf("ААА «Абрикос»", "ЯООО «Яблоко»", UNKNOWN_SUPPLIER_LABEL), groups.map { it.displayName })
    }

    @Test
    fun `без пустых имён хвостовой группы нет`() {
        val groups = groupByParty(listOf("ООО «Ромашка»")) { it }

        assertEquals(1, groups.size)
        assertNotEquals(UNKNOWN_PARTY_KEY, groups.first().key)
    }

    // --- Стабильность ключа и выбор отображаемого имени ---

    @Test
    fun `ключ группы не зависит от того, какое написание победило`() {
        val oneWins = groupByParty(
            listOf("""ООО ТД "Альбия"""", """ООО ТД "Альбия"""", """ООО ТД "АЛЬБИЯ""""),
        ) { it }
        val otherWins = groupByParty(
            listOf("""ООО ТД "АЛЬБИЯ"""", """ООО ТД "АЛЬБИЯ"""", """ООО ТД "Альбия""""),
        ) { it }

        assertNotEquals(oneWins.first().displayName, otherWins.first().displayName)
        assertEquals(
            "ключ обязан пережить смену победившего написания",
            oneWins.first().key,
            otherWins.first().key,
        )
    }

    @Test
    fun `отображается самое частое написание`() {
        val rows = listOf(
            """ООО ТД "Альбия"""",
            """ООО ТД "Альбия"""",
            """ООО ТД "АЛЬБИЯ"""",
            """Общество с ограниченной ответственностью Торговый Дом "Альбия"""",
        )

        val groups = groupByParty(rows) { it }

        assertEquals("""ООО ТД "Альбия"""", groups.first().displayName)
    }

    @Test
    fun `при равной частоте берётся короткое написание`() {
        val rows = listOf(
            """ООО ТД "Альбия"""",
            """Общество с ограниченной ответственностью Торговый Дом "Альбия"""",
        )

        val groups = groupByParty(rows) { it }

        assertEquals("""ООО ТД "Альбия"""", groups.first().displayName)
    }

    @Test
    fun `при равной частоте и длине выбор детерминирован`() {
        val ordered = groupByParty(listOf("""ООО ТД "АЛЬБИЯ"""", """ООО ТД "Альбия"""")) { it }
        val reversed = groupByParty(listOf("""ООО ТД "Альбия"""", """ООО ТД "АЛЬБИЯ"""")) { it }

        assertEquals(ordered.first().displayName, reversed.first().displayName)
    }

    @Test
    fun `отображаемое имя очищено от лишних пробелов`() {
        val groups = groupByParty(listOf("ООО «АЛЮПРОМ» ")) { it }

        assertEquals("ООО «АЛЮПРОМ»", groups.first().displayName)
    }

    // --- Порядок и полнота ---

    @Test
    fun `группы идут по алфавиту без учёта регистра`() {
        val rows = listOf("ооо «бета»", "ООО «Альфа»", "ООО «Гамма»")

        val groups = groupByParty(rows) { it }

        assertEquals(listOf("ООО «Альфа»", "ооо «бета»", "ООО «Гамма»"), groups.map { it.displayName })
    }

    @Test
    fun `ни одна строка не теряется`() {
        val rows = listOf("ООО «Альфа»", "ООО «Альфа»", null, "ООО «Бета»", "")

        val groups = groupByParty(rows) { it }

        assertEquals(rows.size, groups.sumOf { it.rows.size })
    }
}
