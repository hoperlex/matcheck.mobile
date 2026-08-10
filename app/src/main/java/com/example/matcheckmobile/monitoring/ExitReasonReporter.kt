package com.example.matcheckmobile.monitoring

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.matcheckmobile.data.settings.DeviceSettings

/**
 * Отчёт о том, как в прошлый раз завершился процесс приложения.
 *
 * Зачем: залипший success-оверлей инспектор лечит перезапуском, и до сих пор
 * от этого события не оставалось ни одного следа. Здесь мы хотя бы отличаем
 * «приложение реально висело» (REASON_ANR со стеком) от «приложение было живо,
 * а закрыли его руками».
 *
 * Чего этот сигнал НЕ доказывает — важно помнить при чтении журнала:
 *  - REASON_USER_REQUESTED покрывает и force-stop из настроек, и обычный свайп
 *    из Recents; сам по себе он не означает «инспектор боролся с зависанием»;
 *  - отсутствие REASON_ANR НЕ доказывает, что главный поток был отзывчив:
 *    система заводит ANR только когда успевает его зафиксировать.
 * Это дополнительное свидетельство в пользу одной из версий, не более.
 */
object ExitReasonReporter {

    suspend fun reportLastExit(
        context: Context,
        settings: DeviceSettings,
        journal: IncidentJournal,
    ) {
        // getHistoricalProcessExitReasons появился в API 30. minSdk у нас 24,
        // но парк планшетов — Android 28…36, то есть покрытие почти полное.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val records = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        }.getOrNull().orEmpty()
        if (records.isEmpty()) return

        val lastReportedAt = runCatching { settings.readLastExitReportedAt() }.getOrDefault(0L)
        // Записи приходят от новых к старым; берём только то, чего ещё не видели.
        val fresh = records.filter { it.timestamp > lastReportedAt }
        if (fresh.isEmpty()) return

        fresh.sortedBy { it.timestamp }.forEach { info ->
            journal.recordAwait(
                "process_exit",
                "reason" to reasonName(info.reason),
                "reasonCode" to info.reason,
                "importance" to info.importance,
                "status" to info.status,
                "exitAt" to info.timestamp,
                "description" to info.description,
                "anrTrace" to anrTrace(info),
            )
        }

        runCatching { settings.setLastExitReportedAt(fresh.maxOf { it.timestamp }) }
    }

    /**
     * Стек ANR, если система его сохранила. Трейс может отсутствовать или уже
     * быть перезаписан — это штатное отсутствие данных, а не ошибка. Режем по
     * размеру: полный дамп всех потоков в журнал не нужен и не помещается.
     */
    private fun anrTrace(info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR) return null
        return runCatching {
            info.traceInputStream?.use { stream ->
                val buffer = ByteArray(MAX_TRACE_BYTES)
                var read = 0
                while (read < MAX_TRACE_BYTES) {
                    val n = stream.read(buffer, read, MAX_TRACE_BYTES - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) null else String(buffer, 0, read)
            }
        }.onFailure { Log.w(TAG, "anr trace read failed", it) }.getOrNull()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private const val TAG = "ExitReasonReporter"
    private const val MAX_RECORDS = 5
    private const val MAX_TRACE_BYTES = 32 * 1024
}
