package com.example.matcheckmobile.presentation.util

import com.example.matcheckmobile.domain.BusinessTime
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Форматирует epoch‑millis в «HH:mm» в БИЗНЕС-зоне объекта, а не в зоне
 * устройства. Возвращает `null`, если значение `null` или некорректно.
 * Используется и в архиве приёмок, и в архиве отгрузок.
 *
 * Зона именно бизнес-: группировка по дням уже идёт по Москве, и если время
 * на карточке считать по планшету, запись в группе «30.07» могла показывать
 * время, относящееся к другой дате.
 */
fun formatLocalTime(epochMs: Long?): String? {
    if (epochMs == null) return null
    return runCatching {
        Instant.ofEpochMilli(epochMs)
            .atZone(BusinessTime.ZONE)
            .format(HH_MM)
    }.getOrNull()
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
