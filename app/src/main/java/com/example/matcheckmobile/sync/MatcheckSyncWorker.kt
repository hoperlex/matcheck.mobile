package com.example.matcheckmobile.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.auth.SessionGate
import com.example.matcheckmobile.data.settings.DeviceSettings
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
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
        val trigger = inputData.getString(MatcheckSyncScheduler.KEY_TRIGGER)
            ?: MatcheckSyncScheduler.TRIGGER_UNKNOWN
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

        val settings = container.deviceSettings
        // Поколение читаем ДО цикла: триггер, пришедший во время работы,
        // поднимет requested выше, и следующее звено цепочки честно отработает
        // его вместо того, чтобы объявить состояние свежим.
        val requested = settings.readSyncRequestGeneration()
        // Коалесцирование пачки приложенных звеньев. Пять триггеров подряд
        // ставят пять запросов (APPEND_OR_REPLACE — см. планировщик), но цикл
        // нужен один: первое звено отрабатывает поколение, остальные выходят
        // мгновенно и без сети.
        //
        // Периодика НЕ коалесцируется: она backstop, и её задача — отработать
        // независимо от того, сколько ручных триггеров уже закрыто. Иначе
        // единственная страховка на случай молчащего SSE отключалась бы сама
        // собой.
        val processed = settings.readSyncProcessedGeneration()
        if (!shouldRunSyncCycle(trigger, requested, processed)) {
            Log.i(TAG, "sync[$trigger]: поколение $requested уже отработано ($processed), цикл пропущен")
            return Result.success(MatcheckSyncScheduler.outputOf(ok = true, coalesced = true))
        }

        Log.i(TAG, "sync[$trigger]: старт цикла, поколение $requested (обработано $processed)")
        val outcome = container.syncRepository.syncOnce()
        return outcome.fold(
            onSuccess = {
                // Отметку ставим ТОЛЬКО после реально отработавшего цикла:
                // «processed» означает «состояние на это поколение получено»,
                // и на этом обещании держится коалесцирование выше.
                settings.setSyncProcessedGeneration(requested)
                Result.success(MatcheckSyncScheduler.outputOf(ok = true, coalesced = false))
            },
            onFailure = { e ->
                // Транзиентный оффлайн (IOException) — норма, не шлём (шум + квота).
                // Репортим только неожиданные (не сетевые) сбои синка.
                if (e !is IOException) Sentry.captureException(e)
                // Retry ограничен по числу попыток. Инцидент 04.08 (ЖК АЛИЯ):
                // цикл падал начиная с 23:08, WorkManager удваивал паузу
                // (50 → 94 → 168 минут, потолок — 5 часов), а ExistingWorkPolicy.KEEP
                // всё это время отбрасывал команды «синхронизировать сейчас».
                // Очередь простояла 5 ч 15 мин при живой сети. После лимита
                // отдаём success: очередь при этом НЕ теряется (мутации лежат в
                // Room до фактического ответа сервера), а дальше работает
                // обычная 15-минутная периодика вместо многочасового backoff.
                if (shouldGiveUpRetrying(runAttemptCount)) {
                    Log.w(TAG, "sync[$trigger]: попытка $runAttemptCount неуспешна, выходим на периодику: ${e.message}")
                    // success, а НЕ failure — и теперь это несущее свойство, а
                    // не мелочь. Запросы ставятся цепочкой (APPEND_OR_REPLACE),
                    // а FAILED-звено уводит в FAILED все уже приложенные за ним
                    // задачи: один неудачный цикл отменил бы все накопленные
                    // триггеры. Логическая ошибка едет в outputData, статус
                    // задачи остаётся техническим.
                    //
                    // Отметку processed при этом НЕ ставим: цикл не отработал,
                    // и следующее звено обязано попробовать снова.
                    Result.success(
                        MatcheckSyncScheduler.outputOf(
                            ok = false,
                            coalesced = false,
                            error = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                } else {
                    Result.retry()
                }
            },
        )
    }

    private companion object {
        const val TAG = "MatcheckSyncWorker"
    }
}

/**
 * Попытки 0, 1, 2 — retry; начиная с третьей воркер отдаёт success и уступает
 * место обычной 15-минутной периодике. При LINEAR-backoff это примерно
 * 30 + 60 + 90 секунд ожидания вместо удвоения до пятичасового потолка.
 *
 * Очередь при этом не теряется: мутации живут в Room до фактического ответа
 * сервера, их чистит только [MutationProcessor] по успеху или терминальному
 * конфликту.
 *
 * Вынесено верхнеуровневой функцией, чтобы политика покрывалась JVM-тестом:
 * сам воркер требует WorkManager-харнесса.
 */
/**
 * Нужен ли этому воркеру полный цикл, или его поколение уже закрыто соседом.
 *
 * Пять триггеров подряд ставят пять запросов (APPEND_OR_REPLACE в
 * планировщике), но цикл нужен один: первое звено отрабатывает поколение и
 * поднимает `processed`, остальные выходят мгновенно и без сети. Без
 * коалесцирования гарантия доставки триггера обернулась бы пачкой одинаковых
 * проходов на каждое событие SSE.
 *
 * Периодика проходит всегда: она backstop, и её задача — отработать независимо
 * от того, сколько ручных триггеров уже закрыто. Иначе единственная страховка
 * на случай молчащего SSE отключалась бы сама собой.
 *
 * Вынесено верхнеуровневой функцией по той же причине, что и
 * [shouldGiveUpRetrying]: политика проверяется JVM-тестом, а воркер потребовал
 * бы WorkManager-харнесса и DataStore.
 */
internal fun shouldRunSyncCycle(
    trigger: String,
    requestedGeneration: Long,
    processedGeneration: Long,
): Boolean =
    trigger == MatcheckSyncScheduler.TRIGGER_PERIODIC || processedGeneration < requestedGeneration

internal const val MAX_SYNC_RETRY_ATTEMPTS = 3

internal fun shouldGiveUpRetrying(runAttemptCount: Int): Boolean =
    runAttemptCount >= MAX_SYNC_RETRY_ATTEMPTS

/**
 * Исход синхронизации «по жесту».
 *
 * [ok] — цикл реально отработал без ошибки. [coalesced] означает, что цикл
 * выполнило предыдущее звено цепочки, а наше поколение уже закрыто: для
 * инспектора это тоже успех, данные свежие. [error] заполняется только когда
 * цикл дошёл до сервера и получил отказ.
 */
data class SyncGestureResult(
    val state: WorkInfo.State,
    val ok: Boolean,
    val coalesced: Boolean,
    val error: String?,
)

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

    /** Ключ входных данных: чем вызван цикл. Читается воркером и логами. */
    const val KEY_TRIGGER = "trigger"
    const val TRIGGER_IMMEDIATE = "immediate"
    const val TRIGGER_GESTURE = "gesture"
    const val TRIGGER_PERIODIC = "periodic"
    const val TRIGGER_UNKNOWN = "unknown"

    /** Ключи выходных данных: исход цикла для жеста и диагностики. */
    const val KEY_OUT_OK = "ok"
    const val KEY_OUT_COALESCED = "coalesced"
    const val KEY_OUT_ERROR = "error"

    /**
     * Инкремент поколения — запись в DataStore, то есть suspend, а вызывают
     * планировщик отовсюду (SSE, ViewModel, Application). Отдельная область
     * живёт столько же, сколько процесс: работа durable и переживает её смерть.
     */
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun outputOf(ok: Boolean, coalesced: Boolean, error: String? = null): Data =
        workDataOf(
            KEY_OUT_OK to ok,
            KEY_OUT_COALESCED to coalesced,
            KEY_OUT_ERROR to error,
        )

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // LINEAR, а не EXPONENTIAL: пауза растёт по 30 секунд, а не удваивается.
    // Экспонента в паре с потолком WorkManager-а (5 часов) и была тем, что
    // оставило очередь ЖК АЛИЯ стоять полночи (см. doWork).
    private fun buildSyncRequest(trigger: String) = OneTimeWorkRequestBuilder<MatcheckSyncWorker>()
        .setConstraints(constraints)
        .setInputData(workDataOf(KEY_TRIGGER to trigger))
        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        .build()

    /**
     * Поднимает поколение и ставит запрос в цепочку. Порядок обязателен:
     * сначала инкремент, потом постановка — иначе воркер, успевший стартовать,
     * увидел бы старое поколение и посчитал бы себя лишним.
     */
    private suspend fun enqueueImmediate(context: Context, trigger: String): UUID {
        DeviceSettings(context).bumpSyncRequestGeneration()
        val request = buildSyncRequest(trigger)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                ONE_TIME,
                // APPEND_OR_REPLACE, а не KEEP и не REPLACE.
                //
                // KEEP отбрасывал триггер, пришедший во время работы, и он
                // никогда не переигрывался: обновление ждало периодики (15 мин).
                // На бою это и давало «приёмка висит на 2 Этапе другого
                // планшета» — цикл был занят заливкой фото.
                //
                // REPLACE нельзя: отмена после принятого сервером update, но до
                // получения ответа, превращает повтор в ложный OCC-конфликт, а
                // отмена между presign и PUT оставляет фото orphan'ом.
                //
                // APPEND ставит запрос ПОСЛЕ идущего цикла, ничего не отменяя.
                // Пачку приложенных звеньев схлопывает счётчик поколений в
                // воркере — лишние выходят мгновенно и без сети.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        return request.id
    }

    /** Push-then-pull сразу. Дёргается после mutation-ов и SSE-событий. */
    fun requestImmediateSync(context: Context) {
        val appContext = context.applicationContext
        schedulerScope.launch { enqueueImmediate(appContext, TRIGGER_IMMEDIATE) }
    }

    /**
     * Периодика. WorkManager минимум — 15 минут (Doze API restriction), так что
     * чаще не получится. Для «секундной» отзывчивости используем SSE-триггер
     * (Этап 5) и явный requestImmediateSync после локальных мутаций.
     */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<MatcheckSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_PERIODIC))
            // LINEAR и здесь: неудачная периодика не должна отодвигать
            // следующую попытку на часы — интервал сам по себе 15 минут.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Синк «по жесту»: ставит СВОЙ запрос и дожидается именно его.
     *
     * Раньше жест мог «успешно» завершиться на чужом воркере: при
     * `ExistingWorkPolicy.KEEP` наш запрос отбрасывался, и код ждал уже идущую
     * задачу — а она могла пройти pull ДО жеста. Индикатор гас, данные
     * оставались прежними. Теперь запрос ставится через APPEND_OR_REPLACE,
     * то есть гарантированно ПОСЛЕ текущего цикла, и ждём мы свой id.
     *
     * Исход берём из `outputData`, а не из статуса: логическая ошибка синка
     * возвращается как технически успешная задача (иначе FAILED-звено увело бы
     * в FAILED все приложенные за ним запросы), поэтому по `state` отличить
     * «синхронизировались» от «сервер ответил ошибкой» нельзя.
     *
     * Ожидание ограничено [REFRESH_TIMEOUT_MS]: без сети задача остаётся
     * ENQUEUED бесконечно.
     *
     * @return исход цикла или null, если истёк таймаут.
     */
    suspend fun requestImmediateSyncAndAwait(context: Context): SyncGestureResult? =
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            val appContext = context.applicationContext
            val id = enqueueImmediate(appContext, TRIGGER_GESTURE)
            val info = WorkManager.getInstance(appContext)
                .getWorkInfoByIdFlow(id)
                .mapNotNull { it?.takeIf { info -> info.state.isFinished } }
                .first()
            SyncGestureResult(
                state = info.state,
                ok = info.state == WorkInfo.State.SUCCEEDED &&
                    info.outputData.getBoolean(KEY_OUT_OK, true),
                coalesced = info.outputData.getBoolean(KEY_OUT_COALESCED, false),
                error = info.outputData.getString(KEY_OUT_ERROR),
            )
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
