package com.example.matcheckmobile.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val ONE_TIME_WORK_NAME = "matcheck_sync_once"
    private const val PERIODIC_WORK_NAME = "matcheck_sync_periodic"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun requestImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<OperationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<OperationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Снимает уже запланированные legacy-воркеры на устройствах, где их
     * успели зарегистрировать. После перехода на Stage1/Stage2 (новый push
     * через [MutationProcessor]) этот цикл больше не нужен — иначе он
     * пушит остаточные ReceiptSession в api.sendSession() и сервер создаёт
     * мусорные приёмки со статусом not_filled.
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
