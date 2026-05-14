package com.example.matcheckmobile

import android.app.Application
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.UserEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MatcheckApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { seedDefaultsIfNeeded() }
        SyncScheduler.schedulePeriodicSync(this)
    }

    private suspend fun seedDefaultsIfNeeded() {
        container.deviceSettings.ensureDeviceId()
        val siteId = "site-default"
        if (container.database.siteDao().findById(siteId) == null) {
            container.database.siteDao().upsert(
                SiteEntity(
                    localId = siteId,
                    serverId = null,
                    name = "Объект по умолчанию",
                    address = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
            container.deviceSettings.setCurrentSite(siteId)
        }
        val userId = "user-default"
        if (container.database.userDao().findById(userId) == null) {
            container.database.userDao().upsert(
                UserEntity(
                    localId = userId,
                    serverId = null,
                    fullName = "Охранник КПП",
                    email = null,
                    role = "inspector_kpp",
                    isActive = true,
                    createdAt = System.currentTimeMillis(),
                )
            )
            container.deviceSettings.setCurrentUser(userId)
        }
    }
}
