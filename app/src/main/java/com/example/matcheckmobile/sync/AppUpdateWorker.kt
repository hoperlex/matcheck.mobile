package com.example.matcheckmobile.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.MatcheckApplication
import java.util.concurrent.TimeUnit

/**
 * Периодически (раз в ~6 часов) дёргает AppUpdateRepository.checkForUpdate().
 * Сам по себе диалог не показывает — обновляет только StateFlow, которое
 * наблюдает главный экран (MainScreen). Если приложение в фоне, инспектор
 * увидит диалог при следующем открытии.
 *
 * В debug-сборке BuildConfig.UPDATE_CHECK_ENABLED = false, и Repository
 * ничего не делает — Worker безопасно работает, но не приведёт к показу
 * никаких UI-всплытий. Тем не менее планировать его на dev-устройстве
 * смысла нет, поэтому регистрация в [schedule] под condition.
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MatcheckApplication ?: return Result.success()
        app.container.appUpdateRepository.checkForUpdate()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "matcheck-app-update-check"

        fun schedule(context: Context) {
            if (!BuildConfig.UPDATE_CHECK_ENABLED) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
