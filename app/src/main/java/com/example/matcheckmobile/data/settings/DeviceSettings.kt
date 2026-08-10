package com.example.matcheckmobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "device_settings")

class DeviceSettings(private val context: Context) {
    val deviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    val currentUserIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ID] ?: ""
    }

    val currentSiteIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SITE_ID] ?: ""
    }

    val serverBaseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    val syncCursorFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYNC_CURSOR]?.takeIf { it.isNotEmpty() }
    }

    /**
     * Пользовательское предпочтение «Горизонтальный режим» — тумблер на главной.
     * Применяется в MainActivity через requestedOrientation. Layout экранов
     * ориентируется на фактический WindowSizeClass (как должно), а этот флаг —
     * только «попросить систему повернуть устройство».
     *
     * Внимание: на Android 16 для приложений, нацеленных на API 36+, система
     * на больших экранах sw600dp+ может игнорировать requestedOrientation
     * (см. https://developer.android.com/about/versions/16/behavior-changes-16).
     * Сейчас targetSdk=35, поэтому требование пока применяется — но при
     * подъёме targetSdk поведение на планшетах изменится.
     */
    val prefersLandscapeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PREFERS_LANDSCAPE] ?: false
    }

    suspend fun readSyncCursor(): String? {
        return context.dataStore.data.first()[KEY_SYNC_CURSOR]?.takeIf { it.isNotEmpty() }
    }

    suspend fun setSyncCursor(cursor: String) {
        context.dataStore.edit { it[KEY_SYNC_CURSOR] = cursor }
    }

    suspend fun clearSyncCursor() {
        context.dataStore.edit { it.remove(KEY_SYNC_CURSOR) }
    }

    suspend fun ensureDeviceId(): String {
        val current = context.dataStore.data.first()[KEY_DEVICE_ID]
        if (!current.isNullOrEmpty()) return current
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[KEY_DEVICE_ID] = newId }
        return newId
    }

    suspend fun setCurrentUser(userId: String) {
        context.dataStore.edit { it[KEY_USER_ID] = userId }
    }

    suspend fun setCurrentSite(siteId: String) {
        context.dataStore.edit { it[KEY_SITE_ID] = siteId }
    }

    /**
     * Смена объекта у аккаунта замечена: новый siteId и «долг» на частичный
     * сброс snapshot пишутся ОДНОЙ транзакцией DataStore. Раньше намерение
     * жило только в памяти процесса — если приложение убивали между записью
     * siteId и сбросом, планшет навсегда оставался со снимком чужого объекта.
     */
    suspend fun beginSiteChange(newSiteId: String) {
        context.dataStore.edit {
            it[KEY_SITE_ID] = newSiteId
            it[KEY_PENDING_SITE_RESET] = newSiteId
        }
    }

    /** Незавершённый сброс: целевой siteId или null, если долга нет. */
    suspend fun readPendingSiteReset(): String? =
        context.dataStore.data.first()[KEY_PENDING_SITE_RESET]?.takeIf { it.isNotEmpty() }

    suspend fun clearPendingSiteReset() {
        context.dataStore.edit { it.remove(KEY_PENDING_SITE_RESET) }
    }

    /**
     * Очистка данных аккаунта начата, но не подтверждена как полная.
     *
     * Нужен, потому что удаление файлов может не удаться (файл занят, ошибка
     * ФС), а раньше такой сбой глотался внутри и наружу не выходил: токены
     * стирались, а снимки прошлого инспектора оставались на диске. Флаг
     * снимается только после полного успеха; остаток доделывает
     * `AccountSwitchCoordinator.resumePendingWipe` на следующем старте.
     */
    suspend fun setWipePending(value: Boolean) {
        context.dataStore.edit {
            if (value) it[KEY_WIPE_PENDING] = true else it.remove(KEY_WIPE_PENDING)
        }
    }

    suspend fun isWipePending(): Boolean =
        context.dataStore.data.first()[KEY_WIPE_PENDING] ?: false

    suspend fun setServerBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun setPrefersLandscape(value: Boolean) {
        context.dataStore.edit { it[KEY_PREFERS_LANDSCAPE] = value }
    }

    /**
     * Таймстемп последней записи ApplicationExitInfo, уже попавшей в журнал
     * инцидентов. Хранится долговечно, а не в памяти процесса: система отдаёт
     * историю выходов при каждом старте, и без этого маркера одни и те же
     * записи дублировались бы после каждого запуска приложения.
     */
    suspend fun readLastExitReportedAt(): Long =
        context.dataStore.data.first()[KEY_LAST_EXIT_REPORTED_AT] ?: 0L

    suspend fun setLastExitReportedAt(timestampMs: Long) {
        context.dataStore.edit { it[KEY_LAST_EXIT_REPORTED_AT] = timestampMs }
    }

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_USER_ID = stringPreferencesKey("current_user_id")
        private val KEY_SITE_ID = stringPreferencesKey("current_site_id")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SYNC_CURSOR = stringPreferencesKey("sync_cursor")
        private val KEY_PENDING_SITE_RESET = stringPreferencesKey("pending_site_reset")
        private val KEY_WIPE_PENDING = booleanPreferencesKey("wipe_pending")
        private val KEY_PREFERS_LANDSCAPE = booleanPreferencesKey("prefers_landscape")
        private val KEY_LAST_EXIT_REPORTED_AT = longPreferencesKey("last_exit_reported_at")
    }
}
