package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.remote.api.dto.ReconcileMissingOnClientDto
import com.example.matcheckmobile.data.remote.api.dto.ReconcilePerTypeDto
import com.example.matcheckmobile.data.remote.api.dto.ReconcileStaleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор записей на перезабор по ответу сверки.
 *
 * Ради чего набор. Сверка — единственный способ починить УСТАРЕВШУЮ локальную
 * копию: дельта отбирает по updated_at и мимо ушедшего в офлайн планшета
 * проходит, а `staleOnClient` адресован именно ему. Живой сценарий: на портале
 * сорвался разбор, документ уехал на планшет пустым, повтор распознавания
 * прошёл успешно — вернуть инспектору реквизиты может только перезабор.
 *
 * Почему тест отдельный, а не «и так видно». Потеряй кто-нибудь `staleOnClient`
 * из объединения, не упало бы НИЧЕГО: `missingOnClient` остался бы на месте,
 * сверка так же писала бы в лог, а карточки просто продолжили бы показывать
 * прочерки. Такой дефект уже был на сервере и стоил 52 документов.
 */
class ReconcileRefetchTest {

    private fun missing(id: String) = ReconcileMissingOnClientDto(id, version = 1, updatedAt = "2026-08-27T09:00:00Z")
    private fun stale(id: String) = ReconcileStaleDto(id, serverVersion = 2)

    @Test
    fun `устаревшие копии попадают в перезабор вместе с отсутствующими`() {
        val ids = reconcileRefetchIds(
            ReconcilePerTypeDto(
                missingOnClient = listOf(missing("нет-вовсе")),
                staleOnClient = listOf(stale("устарел")),
            ),
        )
        assertEquals(listOf("нет-вовсе", "устарел"), ids)
    }

    @Test
    fun `один только staleOnClient тоже даёт перезабор`() {
        // Ровно случай переразобранного документа: у планшета он ЕСТЬ, но пустой.
        val ids = reconcileRefetchIds(
            ReconcilePerTypeDto(staleOnClient = listOf(stale("переразобран"))),
        )
        assertEquals(listOf("переразобран"), ids)
    }

    @Test
    fun `missingOnServer в перезабор не попадает`() {
        // Обратное расхождение: у приёмок и отгрузок лечится переотправкой
        // мутации, у документов игнорируется. Утащи мы его сюда — планшет
        // пошёл бы дёргать detail по записям, которых на сервере нет.
        val ids = reconcileRefetchIds(
            ReconcilePerTypeDto(missingOnServer = listOf("только-локально")),
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `один и тот же id из обоих списков не даёт двух запросов`() {
        val ids = reconcileRefetchIds(
            ReconcilePerTypeDto(
                missingOnClient = listOf(missing("дубль")),
                staleOnClient = listOf(stale("дубль")),
            ),
        )
        assertEquals(listOf("дубль"), ids)
    }

    @Test
    fun `пустой ответ не порождает работы`() {
        assertTrue(reconcileRefetchIds(ReconcilePerTypeDto()).isEmpty())
    }
}
