package com.example.matcheckmobile.data.remote.net

import android.os.Build
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.AuthApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Собирает Retrofit поверх OkHttp с авторизационными interceptor-ами.
 *
 * AuthApi отдаётся через [authApiLazy] потому, что [TokenAuthenticator]
 * сам делает запрос на /auth/refresh через ту же сеть — и должен иметь
 * клиент, чтобы вызывать его. Если бы AuthApi создавался напрямую,
 * получился бы циклический init.
 */
class NetworkFactory(
    baseUrl: String,
    tokenStorage: TokenStorage,
    onSessionInvalidated: () -> Unit,
) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val userAgent: String = buildUserAgent()

    private val tokenAuthenticator = TokenAuthenticator(
        tokenStorage = tokenStorage,
        authApiProvider = { authApi },
        onSessionInvalidated = onSessionInvalidated,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthHeaderInterceptor(tokenStorage, userAgent))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor(SecretMaskingLogger()).apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    },
                )
            }
        }
        .authenticator(tokenAuthenticator)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }

    fun <T> create(api: Class<T>): T = retrofit.create(api)

    private fun buildUserAgent(): String {
        val version = BuildConfig.VERSION_NAME
        val build = BuildConfig.VERSION_CODE
        val sdk = Build.VERSION.SDK_INT
        return "matcheck-android/$version (Build $build; Android $sdk)"
    }
}
