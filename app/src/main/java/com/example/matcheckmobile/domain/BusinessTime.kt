package com.example.matcheckmobile.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Единая бизнес-зона объектов и производные от неё «сегодня»/«дата записи».
 *
 * Почему не [ZoneId.systemDefault]: настройки времени на планшетах различаются,
 * и один и тот же заезд попадал в разные дни на разных устройствах — архив
 * «не совпадал». Хуже того, зона расходилась ВНУТРИ одного планшета: архив
 * группировался по Москве, а счётчик «Сегодня» и подписи времени считались по
 * устройству, так что карточка могла лежать в группе одного дня и показывать
 * время другого.
 *
 * Все объекты — Москва и область, портал считает отчёты по московскому
 * времени. Появится объект в другой зоне — понадобится поле `timezone` у site
 * (БД, `/sync`, Room); сейчас его нет, и это осознанный выбор, а не упущение.
 *
 * Любая бизнес-дата в приложении обязана идти через этот объект. Прямые
 * `LocalDate.now()` и `ZoneId.systemDefault()` в UI-слое — баг.
 */
object BusinessTime {

    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

    /** Сегодняшняя календарная дата объекта. */
    fun today(): LocalDate = LocalDate.now(ZONE)

    /** Сегодняшняя дата строкой 'YYYY-MM-DD' — формат `expectedDate` у УПД. */
    fun todayIso(): String = today().toString()

    /** Календарная дата момента времени по бизнес-зоне. */
    fun dateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate()

    fun dateOf(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    /** Начало суток [date] в миллисекундах эпохи. */
    fun startOfDayMs(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** Относится ли момент к сегодняшнему дню объекта. */
    fun isToday(epochMs: Long): Boolean = dateOf(epochMs) == today()

    /**
     * Начало окна из [days] календарных дат, включая сегодняшнюю.
     * Для семидневного архива это начало дня «сегодня минус шесть».
     */
    fun windowStartMs(days: Long): Long = startOfDayMs(today().minusDays(days - 1))
}
