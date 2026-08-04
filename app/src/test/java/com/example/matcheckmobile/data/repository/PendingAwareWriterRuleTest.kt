package com.example.matcheckmobile.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правило [pickConfirmedAt]: чьё время подтверждения победит при записи
 * серверного снимка.
 *
 * Регрессия, ради которой оно появилось: время рождается на планшете и живёт
 * локально, пока мутация не доедет до сервера. В этом окне любой серверный
 * снимок (pull, reconcile, ответ upsert, OCC-конфликт) обнулял бы поле, и
 * «выезд» снова исчезал бы из архива. Обратная крайность тоже неверна:
 * бессрочно держать локальное значение — значит законсервировать устаревшее.
 */
class PendingAwareWriterRuleTest {

    private val local = "2026-08-03T20:53:00Z"
    private val server = "2026-08-04T02:23:39Z"

    @Test
    fun `серверное непустое время побеждает всегда`() {
        assertEquals(server, pickConfirmedAt(server = server, local = local, hasQueuedConfirm = true))
        assertEquals(server, pickConfirmedAt(server = server, local = local, hasQueuedConfirm = false))
        assertEquals(server, pickConfirmedAt(server = server, local = null, hasQueuedConfirm = false))
    }

    @Test
    fun `серверный null не стирает локальное, пока мутация в очереди`() {
        assertEquals(local, pickConfirmedAt(server = null, local = local, hasQueuedConfirm = true))
    }

    @Test
    fun `без мутации в очереди сервер авторитетен, включая null`() {
        assertNull(pickConfirmedAt(server = null, local = local, hasQueuedConfirm = false))
    }

    @Test
    fun `нечего сохранять — остаётся пусто`() {
        assertNull(pickConfirmedAt(server = null, local = null, hasQueuedConfirm = true))
        assertNull(pickConfirmedAt(server = null, local = null, hasQueuedConfirm = false))
    }
}
