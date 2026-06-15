package com.example.matcheckmobile.presentation.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Форматирует epoch‑millis в «HH:mm» в локальной зоне устройства. Возвращает
 * `null`, если переданное значение `null` или некорректно. Используется и в
 * архиве приёмок, и в архиве отгрузок — общий формат «13:24» в карточках.
 */
fun formatLocalTime(epochMs: Long?): String? {
    if (epochMs == null) return null
    return runCatching {
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(HH_MM)
    }.getOrNull()
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
