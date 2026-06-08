package com.example.matcheckmobile.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Запоминает последние успешные учётные данные (email + пароль) для
 * автозаполнения экрана входа после выхода из аккаунта.
 *
 * Принципиально отдельное хранилище от [TokenStorage]: при logout
 * TokenStorage.clear() стирает токены, но запомненные креды должны
 * пережить выход — иначе автозаполнение теряет смысл. Чистятся отдельно
 * (в текущем UX — никогда автоматически; пользователь просто перезайдёт).
 *
 * Пароль лежит в EncryptedSharedPreferences (Jetpack Security, AES-256) —
 * тот же уровень защиты, что и refresh-token. Это сознательный trade-off
 * под текущий масштаб (десяток инспекторов на доверенных планшетах ради
 * быстрого повторного входа), а не общая рекомендация хранить пароли.
 */
class RememberedCredentialsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val email: String? get() = prefs.getString(KEY_EMAIL, null)
    val password: String? get() = prefs.getString(KEY_PASSWORD, null)

    fun save(email: String, password: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "matcheck_remembered_creds"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
