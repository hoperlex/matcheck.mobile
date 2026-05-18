package com.example.matcheckmobile.data.remote.net

import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.AuthApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException

/**
 * Перехватывает 401 на бизнес-эндпоинтах и пытается обновить access-token
 * через /auth/refresh. Параллельные 401 не должны вызывать гонку: refresh
 * сериализуется через [refreshMutex]; запросы, словившие 401 после успешного
 * refresh-а, повторяются с новым access-токеном.
 *
 * /auth/login и /auth/refresh из этого механизма исключены: их 401 — это
 * "плохие учётные данные" / "refresh уже использован", их обрабатывает
 * вызывающий код.
 *
 * При неудаче refresh-а: чистим TokenStorage и пробрасываем событие,
 * чтобы UI ушёл на login.
 */
class TokenAuthenticator(
    private val tokenStorage: TokenStorage,
    private val authApiProvider: () -> AuthApi,
    private val onSessionInvalidated: () -> Unit,
) : Authenticator {

    private val refreshMutex = Mutex()

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
            refreshMutex.withLock {
                val current = tokenStorage.accessToken
                if (current != null && current != storedAccessAtFailure) {
                    // Параллельный поток уже обновил токен — берём его.
                    return@withLock request.newBuilder()
                        .header("Authorization", "Bearer $current")
                        .build()
                }

                val refresh = tokenStorage.refreshToken ?: run {
                    onSessionInvalidated()
                    return@withLock null
                }

                try {
                    val r = authApiProvider().refresh("Bearer $refresh")
                    tokenStorage.saveRefreshedTokens(
                        accessToken = r.accessToken,
                        accessExpiresInSec = r.expiresIn,
                        refreshToken = r.refreshToken,
                        refreshExpiresInSec = r.refreshExpiresIn,
                    )
                    request.newBuilder()
                        .header("Authorization", "Bearer ${r.accessToken}")
                        .build()
                } catch (e: HttpException) {
                    // 401 invalid_refresh / no_refresh — сессия мертва.
                    if (e.code() == 401) {
                        tokenStorage.clear()
                        onSessionInvalidated()
                    }
                    null
                } catch (_: Throwable) {
                    // Сеть — оставляем 401, UI пусть повторит позже.
                    null
                }
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
