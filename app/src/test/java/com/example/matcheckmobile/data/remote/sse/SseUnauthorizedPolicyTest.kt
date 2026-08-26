package com.example.matcheckmobile.data.remote.sse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда канал уведомлений обновляет токен, а когда просто ждёт.
 *
 * Цена ошибки здесь несимметрична. Не обновить — канал ляжет и планшет перестанет
 * узнавать о чужих приёмках (замер 26.08: до 843 секунд молчания). Обновить лишний
 * раз — две ротации refresh-токена подряд, а это для сервера признак атаки: он гасит
 * сессию, и инспектора выкидывает на экран входа посреди смены.
 */
class SseUnauthorizedPolicyTest {

    @Test
    fun `первый отказ по протухшему токену - обновляемся`() {
        assertTrue(shouldRefreshOnUnauthorized(httpCode = 401, failedToken = "A", alreadyRefreshedTo = null))
    }

    @Test
    fun `отказ по только что полученному токену - второй раз не обновляемся`() {
        // Цепочка: A получил 401 → обновление вернуло B → B тоже получил 401.
        // Значит дело не в свежести токена, и ротировать снова нельзя.
        assertFalse(shouldRefreshOnUnauthorized(httpCode = 401, failedToken = "B", alreadyRefreshedTo = "B"))
    }

    @Test
    fun `отказ по ДРУГОМУ токену после обновления - обновляемся`() {
        // Защита привязана к конкретному токену, а не к факту «мы уже обновлялись».
        // Иначе одно неудачное обновление заперло бы канал в backoff навсегда.
        assertTrue(shouldRefreshOnUnauthorized(httpCode = 401, failedToken = "C", alreadyRefreshedTo = "B"))
    }

    @Test
    fun `не-401 обновления не требует`() {
        // Обрыв сети, 500, таймаут — токен ни при чём, нужен обычный backoff.
        assertFalse(shouldRefreshOnUnauthorized(httpCode = 500, failedToken = "A", alreadyRefreshedTo = null))
        assertFalse(shouldRefreshOnUnauthorized(httpCode = null, failedToken = "A", alreadyRefreshedTo = null))
    }

    @Test
    fun `отказ без известного токена соединения - обновлять нечего`() {
        // Соединение не успело зафиксировать свой токен: обновлять вслепую
        // означало бы ротировать чужой.
        assertFalse(shouldRefreshOnUnauthorized(httpCode = 401, failedToken = null, alreadyRefreshedTo = null))
    }
}
