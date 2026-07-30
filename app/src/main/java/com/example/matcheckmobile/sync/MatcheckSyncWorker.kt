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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.auth.SessionGate
import io.sentry.Sentry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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
        if (SessionGate.isSwitching()) {
            // Идёт смена аккаунта: локальная база относится к прошлому
            // пользователю, а токен уже может быть новым. Отправлять сейчас
            // нельзя — записи уехали бы на чужой объект. После активации новой
            // сессии LoginViewModel сам дёрнет requestImmediateSync.
            return Result.success()
        }
        val outcome = container.syncRepository.syncOnce()
        return outcome.fold(
            onSuccess = { Result.success() },
            // Любая ошибка (сеть / 5xx) → retry с экспоненциальным backoff WorkManager-а.
            onFailure = { e ->
                // Транзиентный оффлайн (IOException) — норма, не шлём (шум + квота).
                // Репортим только неожиданные (не сетевые) сбои синка.
                if (e !is IOException) Sentry.captureException(e)
                Result.retry()
            },
        )
    }
}

object MatcheckSyncScheduler {
    // Уникальные имена WorkManager-задач. Public, чтобы UI мог подписаться на
    // состояние (RUNNING / ENQUEUED / SUCCEEDED) и показывать индикатор
    // активности в чипе синхронизации.
    const val ONE_TIME = "matcheck_remote_sync_once"
    const val PERIODIC = "matcheck_remote_sync_periodic"

    /**
     * Общий потолок ожидания для синка «по жесту». Без него pull-to-refresh
     * висел бы вечно: в авиарежиме задача с CONNECTED-constraint остаётся
     * ENQUEUED и никогда не становится finished.
     */
    private const val REFRESH_TIMEOUT_MS = 60_000L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun buildSyncRequest() = OneTimeWorkRequestBuilder<MatcheckSyncWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    /** Push-then-pull сразу. Дёргается после mutation-ов и SSE-событий. */
    fun requestImmediateSync(context: Context) {
        val request = buildSyncRequest()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                ONE_TIME,
                // KEEP, а НЕ REPLACE: внутри синка идёт загрузка фото
                // (presign → PUT в S3 → confirm), и REPLACE отменял текущий
                // воркер сразу после presign — PUT не успевал, фото навсегда
                // оставалось orphan'ом (uploaded_at=null). С KEEP новый триггер
                // (SSE / mutation / foreground), пока синк ещё идёт, НЕ прерывает
                // его — пропускается, текущая заливка доходит до конца. Пропуск
                // не теряет данные: мутации лежат в durable-очереди, а пропущенный
                // апдейт догонит следующий триггер/периодика (15 мин — backstop).
                // Большинство синков быстрые (фото — единственная долгая часть),
                // так что пропуски редки и только в окно заливки фото.
                ExistingWorkPolicy.KEEP,
                request,
            )
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

    /**
     * Синк «по жесту»: ставит задачу и ДОЖИДАЕТСЯ завершения КОНКРЕТНОЙ
     * активной задачи, возвращая её финальное состояние. Нужен pull-to-refresh:
     * индикатор должен гаснуть по факту синхронизации, а не по факту постановки
     * в очередь, а инспектор — видеть ошибку вместо «тихого ничего».
     *
     * Почему именно по id, а не «пока все finished»:
     *  - `lastOrNull()` из истории отдаёт состояние ПРОШЛОЙ задачи, и жест
     *    завершался бы мгновенно;
     *  - при `ExistingWorkPolicy.KEEP` наш запрос может быть отброшен, если синк
     *    уже идёт — тогда ждать надо чужой активный id.
     *
     * Всё ожидание ограничено [REFRESH_TIMEOUT_MS]: без сети задача остаётся
     * ENQUEUED бесконечно.
     *
     * @return финальное состояние задачи или null, если истёк таймаут.
     */
    suspend fun requestImmediateSyncAndAwait(context: Context): WorkInfo.State? =
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            val wm = WorkManager.getInstance(context)
            val infos = wm.getWorkInfosForUniqueWorkFlow(ONE_TIME)
            // Активная задача ДО постановки: если синк уже идёт, KEEP отбросит
            // наш запрос и ждать нужно именно её.
            val runningBefore = infos.first().firstOrNull { !it.state.isFinished }?.id

            val request = buildSyncRequest()
            wm.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)

            val targetId = runningBefore ?: infos
                .mapNotNull { list ->
                    // Наш запрос принят — ждём его; иначе (KEEP-гонка) берём
                    // любой активный. Завершившийся «наш» тоже подходит: значит
                    // синк успел отработать, пока мы подписывались.
                    list.firstOrNull { it.id == request.id }?.id
                        ?: list.firstOrNull { !it.state.isFinished }?.id
                }
                .first()

            infos
                .mapNotNull { list -> list.firstOrNull { it.id == targetId && it.state.isFinished } }
                .first()
                .state
        }

    /**
     * Снимает обе sync-задачи и ДОЖИДАЕТСЯ, пока WorkManager зафиксирует
     * отмену. Используется при смене аккаунта: очищать локальную базу можно
     * только после того, как ни один воркер уже не запустится.
     *
     * Отмена не гарантирует, что УЖЕ работающий syncOnce мгновенно прервался,
     * поэтому вызывающая сторона дополнительно берёт sync-мьютекс
     * (SyncRepository.runExclusively) — он и есть настоящий барьер.
     */
    suspend fun cancelSyncWorkAndAwait(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_TIME).await()
        wm.cancelUniqueWork(PERIODIC).await()
    }
}
