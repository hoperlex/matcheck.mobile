package com.example.matcheckmobile.data.remote.net

import com.example.matcheckmobile.data.auth.TokenStorage
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
    private val tokenStorage: TokenStorage,
    private val userAgent: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header(HEADER_CLIENT_TYPE, "mobile")
            .header(HEADER_USER_AGENT, userAgent)

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
