package com.example.matcheckmobile.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.matcheckmobile.MatcheckApplication
import java.util.concurrent.TimeUnit

/**
 * Push-then-pull worker для серверной модели Delivery/Shipment.
 *
 * Не путать с legacy [OperationSyncWorker] — тот работает над старыми
 * таблицами (receipt_sessions / material_operations) и будет удалён в Этапе 6
 * после переключения UI.
 */
class MatcheckSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MatcheckApplication).container
        if (!container.tokenStorage.isAuthenticated()) {
            // Без сессии работать нечем — выходим без ошибки, чтобы
            // периодик-таска не запоминалась как failed.
            return Result.success()
        }
        val outcome = container.syncRepository.syncOnce()
        return outcome.fold(
            onSuccess = { Result.success() },
            // Любая ошибка (сеть / 5xx) → retry с экспоненциальным backoff WorkManager-а.
            onFailure = { Result.retry() },
        )
    }
}

object MatcheckSyncScheduler {
    private const val ONE_TIME = "matcheck_remote_sync_once"
    private const val PERIODIC = "matcheck_remote_sync_periodic"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Push-then-pull сразу. Дёргается после mutation-ов и SSE-событий. */
    fun requestImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<MatcheckSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Периодика. WorkManager минимум — 15 минут (Doze API restriction), так что
     * чаще не получится. Для «секундной» отзывчивости используем SSE-триггер
     * (Этап 5) и явный requestImmediateSync после локальных мутаций.
     */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<MatcheckSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
