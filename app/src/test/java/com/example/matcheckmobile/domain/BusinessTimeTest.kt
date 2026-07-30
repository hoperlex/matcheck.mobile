package com.example.matcheckmobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Бизнес-дата не должна зависеть от настроек планшета.
 *
 * Раньше зона расходилась внутри одного устройства: архив группировался по
 * Москве, а «Сегодня» и подписи времени считались по `ZoneId.systemDefault()`.
 * На планшете с другой зоной карточка попадала в группу одного дня и
 * показывала время другого.
 */
class BusinessTimeTest {

    @Test
    fun `зона фиксирована московской`() {
        assertEquals(ZoneId.of("Europe/Moscow"), BusinessTime.ZONE)
    }

    @Test
    fun `дата не зависит от зоны устройства`() {
        // 30 июля 22:30 UTC — это уже 31 июля по Москве (UTC+3), но всё ещё
        // 30 июля в Лондоне и 31-е во Владивостоке. Ответ обязан быть один.
        val moment = Instant.parse("2026-07-30T22:30:00Z")

        assertEquals(LocalDate.of(2026, 7, 31), BusinessTime.dateOf(moment))
        assertNotEquals(
            "иначе на планшете с зоной UTC запись уехала бы в предыдущий день",
            LocalDate.of(2026, 7, 31),
            moment.atZone(ZoneId.of("UTC")).toLocalDate(),
        )
    }

    @Test
    fun `граница суток — 00 00 по Москве`() {
        // 20:59:59 UTC = 23:59:59 МСК (ещё 30-е), 21:00:00 UTC = 00:00 МСК 31-го.
        assertEquals(
            LocalDate.of(2026, 7, 30),
            BusinessTime.dateOf(Instant.parse("2026-07-30T20:59:59Z")),
        )
        assertEquals(
            LocalDate.of(2026, 7, 31),
            BusinessTime.dateOf(Instant.parse("2026-07-30T21:00:00Z")),
        )
    }

    @Test
    fun `окно семи дат покрывает ровно семь календарных дней`() {
        val start = BusinessTime.windowStartMs(7)
        val startDate = BusinessTime.dateOf(start)

        assertEquals(BusinessTime.today().minusDays(6), startDate)
        // Начало окна — полночь, а не «минус 168 часов от сейчас».
        assertEquals(BusinessTime.startOfDayMs(startDate), start)
    }

    @Test
    fun `запись ровно семь дней назад в окно не входит`() {
        val window = BusinessTime.windowStartMs(7)
        val sevenDaysAgo = BusinessTime.startOfDayMs(BusinessTime.today().minusDays(7))
        val sixDaysAgo = BusinessTime.startOfDayMs(BusinessTime.today().minusDays(6))

        assertTrue("седьмой день назад — уже за окном", sevenDaysAgo < window)
        assertTrue("шестой день назад — внутри", sixDaysAgo >= window)
    }

    @Test
    fun `isToday согласован с dateOf`() {
        val noonToday = BusinessTime.startOfDayMs(BusinessTime.today()) + 12 * 3600_000L
        val noonYesterday = BusinessTime.startOfDayMs(BusinessTime.today().minusDays(1))

        assertTrue(BusinessTime.isToday(noonToday))
        assertTrue(!BusinessTime.isToday(noonYesterday))
    }
}
