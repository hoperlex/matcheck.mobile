package com.example.matcheckmobile.data.remote.net

import com.example.matcheckmobile.data.auth.SessionTokens
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Проставляет на каждый запрос обязательные mobile-заголовки.
 *
 * Authorization добавляется автоматически только если запрос его ещё не
 * содержит — для /auth/refresh мы шлём Bearer <refreshToken> руками,
 * для /auth/login Authorization не нужен вообще.
 */
class AuthHeaderInterceptor(
    // SessionTokens, а не TokenStorage: перехватчику нужен только access-токен,
    // а интерфейс позволяет проверить заголовки JVM-тестом без Android.
    private val tokenStorage: SessionTokens,
    private val userAgent: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header(HEADER_CLIENT_TYPE, "mobile")
            .header(HEADER_USER_AGENT, userAgent)
            // На каждый запрос, включая refresh и повтор старых мутаций: сервер
            // судит о сборке по тому, кто отправляет запрос. См. ClientCapabilities.
            .header(ClientCapabilities.HEADER, ClientCapabilities.ALL)

        if (original.header(HEADER_AUTH) == null) {
            tokenStorage.accessToken?.let { token ->
                builder.header(HEADER_AUTH, "Bearer $token")
            }
        }

        return chain.proceed(builder.build())
    }

    private companion object {
        const val HEADER_CLIENT_TYPE = "X-Client-Type"
        const val HEADER_USER_AGENT = "User-Agent"
        const val HEADER_AUTH = "Authorization"
    }
}
