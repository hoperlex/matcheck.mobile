package com.example.matcheckmobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
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

    suspend fun setServerBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_USER_ID = stringPreferencesKey("current_user_id")
        private val KEY_SITE_ID = stringPreferencesKey("current_site_id")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    }
}
