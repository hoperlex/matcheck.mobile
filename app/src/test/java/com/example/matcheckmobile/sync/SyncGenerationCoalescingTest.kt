package com.example.matcheckmobile.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Коалесцирование цепочки sync-запросов по поколению.
 *
 * Контекст. Раньше триггер, пришедший во время работы воркера, отбрасывался
 * политикой KEEP и не переигрывался: обновление ждало 15-минутной периодики.
 * На бою это давало «приёмка висит на 2 Этапе другого планшета» — цикл был
 * занят заливкой фото (приёмка 11786, 24.08: 35 секунд от нажатия «Завершить
 * 2 Этап» до статуса на сервере).
 *
 * Доставку теперь обеспечивает APPEND_OR_REPLACE: запрос ставится ПОСЛЕ
 * идущего цикла, ничего не отменяя. Обратная сторона — пачка одинаковых
 * звеньев на каждый SSE-пакет, и схлопывает её этот предикат.
 */
class SyncGenerationCoalescingTest {

    @Test
    fun `незакрытое поколение выполняется`() {
        assertTrue(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_IMMEDIATE, 5, 4))
        assertTrue(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_GESTURE, 1, 0))
    }

    @Test
    fun `уже закрытое поколение пропускается`() {
        // Ровно этот случай — второе и последующие звенья цепочки: первое
        // подняло processed до своего requested, остальным работать нечего.
        assertFalse(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_IMMEDIATE, 5, 5))
        assertFalse(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_GESTURE, 5, 7))
    }

    @Test
    fun `периодика проходит всегда — она backstop`() {
        // Если бы периодика коалесцировалась, единственная страховка на случай
        // молчащего SSE отключалась бы сама собой: активная работа инспектора
        // держала бы processed на уровне requested бесконечно.
        assertTrue(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_PERIODIC, 5, 5))
        assertTrue(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_PERIODIC, 0, 0))
    }

    @Test
    fun `неизвестный триггер не считается периодикой`() {
        // Пустые входные данные (старая задача из очереди WorkManager, пережившая
        // обновление) не должны молча получать привилегию backstop-а.
        assertFalse(shouldRunSyncCycle(MatcheckSyncScheduler.TRIGGER_UNKNOWN, 3, 3))
    }
}
