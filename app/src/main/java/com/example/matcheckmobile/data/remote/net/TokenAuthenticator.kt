package com.example.matcheckmobile.data.remote.net

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Перехватывает 401 на бизнес-эндпоинтах и повторяет запрос с обновлённым
 * access-токеном.
 *
 * Само обновление живёт не здесь, а в общем [TokenRefreshCoordinator]: раньше у
 * этого класса был приватный мьютекс, невидимый для `AuthRepository.refreshNow`
 * и SSE, и три точки обновления могли ротировать один refresh-токен наперегонки.
 * Сервер считает такой повтор атакой и гасит сессию — то есть ложный разлогин.
 *
 * /auth/login, /auth/refresh и /auth/logout из механизма исключены: их 401 — это
 * «плохие учётные данные» либо «refresh уже использован», их разбирает вызывающий.
 * Дополнительно refresh теперь уходит через отдельный клиент (см. NetworkFactory),
 * поэтому попасть сюда рекурсивно он не может в принципе.
 */
class TokenAuthenticator(
    private val coordinator: TokenRefreshCoordinator,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val path = request.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh") ||
            path.endsWith("/auth/logout")
        ) {
            return null
        }
        if (responseCount(response) >= 2) {
            // Уже пытались повторить с обновлённым токеном — больше не лупимся.
            return null
        }

        val storedAccessAtFailure = request.header("Authorization")
            ?.removePrefix("Bearer ")

        return runBlocking {
            when (val outcome = coordinator.obtainFresh(storedAccessAtFailure, path)) {
                is TokenRefreshCoordinator.Outcome.Fresh ->
                    request.newBuilder()
                        .header("Authorization", "Bearer ${outcome.accessToken}")
                        .build()

                // Сессию сменили, пока мы ждали обновления. Повторять НЕЛЬЗЯ:
                // запрос принадлежит прежнему пользователю, и отправить его с
                // новым токеном значило бы выполнить действие одного человека
                // от имени другого.
                TokenRefreshCoordinator.Outcome.SessionChanged -> null

                // Сессия мертва либо обновляться нечем — координатор уже погасил
                // её и уведомил ровно один раз. Отдаём 401 наверх.
                TokenRefreshCoordinator.Outcome.Invalid,
                TokenRefreshCoordinator.Outcome.NoSession -> null

                // Сеть: оставляем 401, вызывающий повторит позже. Сессию не трогаем.
                TokenRefreshCoordinator.Outcome.NetworkError -> null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
