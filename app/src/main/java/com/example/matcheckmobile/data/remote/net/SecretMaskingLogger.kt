package com.example.matcheckmobile.data.remote.net

import okhttp3.logging.HttpLoggingInterceptor
import android.util.Log

/**
 * Адаптер для HttpLoggingInterceptor: маскирует JSON-поля с токенами/паролями,
 * прежде чем что-либо попадает в logcat. Используется только в debug-сборке.
 */
class SecretMaskingLogger : HttpLoggingInterceptor.Logger {

    override fun log(message: String) {
        val masked = SENSITIVE_PATTERNS.fold(message) { acc, (regex, replacement) ->
            regex.replace(acc, replacement)
        }
        Log.d(TAG, masked)
    }

    private companion object {
        const val TAG = "matcheck-http"

        val SENSITIVE_PATTERNS = listOf(
            Regex("(\"accessToken\"\\s*:\\s*)\"[^\"]+\"") to "$1\"***\"",
            Regex("(\"refreshToken\"\\s*:\\s*)\"[^\"]+\"") to "$1\"***\"",
            Regex("(\"password\"\\s*:\\s*)\"[^\"]+\"") to "$1\"***\"",
            Regex("(Authorization:\\s*Bearer\\s+)\\S+") to "$1***",
        )
    }
}
