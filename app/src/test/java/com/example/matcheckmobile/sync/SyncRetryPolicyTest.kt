package com.example.matcheckmobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика повторов синка.
 *
 * Инцидент 04.08 (ЖК АЛИЯ): цикл начал падать в 23:08, WorkManager удваивал
 * паузу (50 → 94 → 168 минут при потолке в 5 часов), и очередь простояла
 * 5 ч 15 мин при живой сети. Ограничиваем число повторов: после лимита воркер
 * отдаёт success и уступает место 15-минутной периодике, а мутации остаются в
 * Room до фактического ответа сервера.
 */
class SyncRetryPolicyTest {

    @Test
    fun `первые попытки повторяются`() {
        assertFalse(shouldGiveUpRetrying(0))
        assertFalse(shouldGiveUpRetrying(1))
        assertFalse(shouldGiveUpRetrying(2))
    }

    @Test
    fun `после лимита уходим на периодику вместо многочасового backoff`() {
        assertTrue(shouldGiveUpRetrying(MAX_SYNC_RETRY_ATTEMPTS))
        assertTrue(shouldGiveUpRetrying(MAX_SYNC_RETRY_ATTEMPTS + 5))
    }

    @Test
    fun `лимит держим небольшим — иначе смысл правки теряется`() {
        assertEquals(3, MAX_SYNC_RETRY_ATTEMPTS)
    }
}
