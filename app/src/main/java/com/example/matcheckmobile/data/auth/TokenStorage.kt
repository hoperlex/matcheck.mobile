package com.example.matcheckmobile.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит токены и профиль текущего сеанса. Refresh-token обязан лежать
 * в EncryptedSharedPreferences (Jetpack Security), access-token достаточно
 * хранить там же — пережить рестарт процесса проще.
 *
 * Жизненный цикл: при логине пишем всё, при logout / invalid_refresh — clear().
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    val accessToken: String? get() = _state.value.accessToken
    val refreshToken: String? get() = _state.value.refreshToken

    fun isAuthenticated(): Boolean = _state.value.refreshToken != null

    /** Полный набор данных после успешного login. */
    @Synchronized
    fun saveSession(
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String,
        refreshExpiresInSec: Long,
        userId: String,
        userEmail: String,
        role: String,
        siteId: String?,
    ) {
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putLong(KEY_ACCESS_EXPIRES_AT, now + accessExpiresInSec * 1000L)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_REFRESH_EXPIRES_AT, now + refreshExpiresInSec * 1000L)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, userEmail)
            putString(KEY_ROLE, role)
            putString(KEY_SITE_ID, siteId)
            apply()
        }
        _state.value = readSnapshot()
    }

    /** Обновление пары токенов после успешного /auth/refresh. */
    @Synchronized
    fun saveRefreshedTokens(
        accessToken: String,
        accessExpiresInSec: Long,
        refreshToken: String?,
        refreshExpiresInSec: Long?,
    ) {
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putLong(KEY_ACCESS_EXPIRES_AT, now + accessExpiresInSec * 1000L)
            if (refreshToken != null && refreshExpiresInSec != null) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
                putLong(KEY_REFRESH_EXPIRES_AT, now + refreshExpiresInSec * 1000L)
            }
            apply()
        }
        _state.value = readSnapshot()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _state.value = readSnapshot()
    }

    private fun readSnapshot(): Snapshot = Snapshot(
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, null),
        accessExpiresAt = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L).takeIf { it > 0 },
        refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
        refreshExpiresAt = prefs.getLong(KEY_REFRESH_EXPIRES_AT, 0L).takeIf { it > 0 },
        userId = prefs.getString(KEY_USER_ID, null),
        userEmail = prefs.getString(KEY_USER_EMAIL, null),
        role = prefs.getString(KEY_ROLE, null),
        siteId = prefs.getString(KEY_SITE_ID, null),
    )

    data class Snapshot(
        val accessToken: String?,
        val accessExpiresAt: Long?,
        val refreshToken: String?,
        val refreshExpiresAt: Long?,
        val userId: String?,
        val userEmail: String?,
        val role: String?,
        val siteId: String?,
    )

    private companion object {
        const val FILE_NAME = "matcheck_secure_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_ROLE = "role"
        const val KEY_SITE_ID = "site_id"
    }
}
