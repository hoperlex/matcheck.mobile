package com.example.matcheckmobile.data.remote.net

import android.os.Build
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.remote.auth.AuthApi
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
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
    onSessionInvalidated: (triggeredByPath: String, httpCode: Int) -> Unit,
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
        .certificatePinner(buildCertificatePinner())
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

    private fun buildCertificatePinner(): CertificatePinner =
        CertificatePinner.Builder()
            // Let's Encrypt E7 — текущий ECDSA intermediate, действует до 2027-03-12.
            .add(PROD_HOST, "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=")
            // ISRG Root X1 — RSA-корень LE, действителен до 2035-06-04. Резерв на
            // случай ротации intermediate (E7 → E8/E9), пока цепочка идёт через RSA-root.
            .add(PROD_HOST, "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=")
            // ISRG Root X2 — ECDSA-корень LE, действителен до 2035-09-04. Резерв на
            // случай миграции LE на ECDSA-only chain.
            .add(PROD_HOST, "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=")
            .build()

    private companion object {
        // Домен мигрировал с matcheck.fvds.ru (старый сервер на FirstVDS) на
        // mat.su10.ru. Пины ниже — CA-цепочка Let's Encrypt (intermediate +
        // корни ISRG X1/X2), они валидируют любой LE-сертификат, поэтому при
        // смене хоста менять сами пины не нужно (mat.su10.ru тоже на LE).
        private const val PROD_HOST = "mat.su10.ru"
    }
}
