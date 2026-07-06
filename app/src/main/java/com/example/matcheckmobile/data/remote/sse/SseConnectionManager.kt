package com.example.matcheckmobile.data.remote.sse

import android.content.Context
import android.util.Log
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.remote.api.dto.SseEventPayload
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Управляет SSE-подключением к `/api/v1/events`. По спецификации MOBILE_API.md:
 *
 * - События `*_updated` → триггер `MatcheckSyncScheduler.requestImmediateSync()`.
 *   Само событие данных не несёт — актуальное состояние подтянет /sync.
 * - События `*_deleted` → сразу удалить локальную запись по `entityId`
 *   (быстрее полной ресинхронизации).
 * - `ping` каждые 25 сек → марк "alive". Если ping молчит > 60 сек —
 *   соединение мёртвое, реконнект с jitter 2-10 сек.
 * - 401 → пробуем reconnect (TokenAuthenticator не работает для SSE,
 *   но если refresh-token валиден, очередное reconnect возьмёт обновлённый
 *   access из TokenStorage). Если refresh нет — обнуляем активность.
 *
 * Lifecycle: запускается на login / при холодном старте с сессией, останавливается
 * на logout. Между подключениями есть jitter, чтобы не штормить сервер при
 * массовых отключениях.
 */
class SseConnectionManager(
    private val baseUrl: String,
    private val tokenStorage: TokenStorage,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val sourceDocumentDao: RemoteSourceDocumentDao,
    private val appContext: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Без TokenAuthenticator (он не нужен — мы сами обрабатываем 401 через
     * reconnect). readTimeout=0 для long-lived потока: иначе OkHttp закроет
     * соединение через 30 сек "idle".
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val lastPingAtMs = AtomicLong(0L)

    private var currentEventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    // Дебаунс SSE-триггера: всплеск *_updated коалесцируем в один sync.
    @Volatile
    private var syncDebounceJob: Job? = null
    // Счётчик неудачных reconnect-попыток для экспоненциального backoff.
    // Сбрасывается в 0 при успешном onOpen.
    private var reconnectAttempts = 0

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        watchdogJob = scope.launch { watchdog() }
        scope.launch { openSource() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        syncDebounceJob?.cancel()
        syncDebounceJob = null
        currentEventSource?.cancel()
        currentEventSource = null
        _state.value = ConnectionState.Disconnected
    }

    private fun openSource() {
        if (!running.get()) return
        val token = tokenStorage.accessToken
        if (token == null) {
            _state.value = ConnectionState.Disconnected
            return
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v1/events")
            .header("Authorization", "Bearer $token")
            .header("X-Client-Type", "mobile")
            .build()
        _state.value = ConnectionState.Connecting
        currentEventSource = EventSources.createFactory(client).newEventSource(request, listener)
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            lastPingAtMs.set(System.currentTimeMillis())
            reconnectAttempts = 0 // успешное подключение — сбрасываем backoff
            _state.value = ConnectionState.Connected
            Log.i(TAG, "SSE connected")
            // После (ре)подключения догоняем изменения, пропущенные за время
            // разрыва канала. requestImmediateSync дедуплицируется WorkManager'ом
            // (ExistingWorkPolicy.REPLACE) — лавины параллельных sync не будет.
            Log.i(TAG, "sync triggered by SSE (re)connect")
            MatcheckSyncScheduler.requestImmediateSync(appContext)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            lastPingAtMs.set(System.currentTimeMillis())
            val payload = runCatching {
                json.decodeFromString(SseEventPayload.serializer(), data)
            }.getOrNull()
            when (type) {
                "ping" -> Unit // только обновляем lastPingAt — уже сделали выше
                "delivery_updated",
                "shipment_updated",
                "source_document_updated",
                "counterparty_updated",
                "material_updated",
                "site_updated",
                // user_updated приходит, когда админ сменил siteId/isActive
                // у инспектора. SyncRepository.syncOnce внутри подтянет
                // свежий /me → tokenStorage.updateSiteId, и штамп объекта
                // на фото 1 Этапа сразу станет актуальным — без logout/login.
                "user_updated" -> {
                    // Всплеск событий коалесцируем в один sync (requestSyncDebounced):
                    // при активной работе на объекте *_updated сыпется пачками, а
                    // WorkManager KEEP всё равно отбросил бы часть enqueue во время
                    // идущего синка. Дебаунс гарантирует один sync ПОСЛЕ всплеска.
                    requestSyncDebounced()
                }
                "delivery_deleted" -> payload?.entityId?.let { id ->
                    scope.launch { deliveryDao.deleteByIds(listOf(id)) }
                }
                "shipment_deleted" -> payload?.entityId?.let { id ->
                    scope.launch { shipmentDao.deleteByIds(listOf(id)) }
                }
                "source_document_deleted" -> payload?.entityId?.let { id ->
                    scope.launch { sourceDocumentDao.deleteByIds(listOf(id)) }
                }
                else -> Unit
            }
        }

        override fun onClosed(eventSource: EventSource) {
            _state.value = ConnectionState.Disconnected
            Log.i(TAG, "SSE closed")
            if (running.get()) scheduleReconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            _state.value = ConnectionState.Disconnected
            // Без персональных данных: только тип ошибки + HTTP-код.
            Log.w(TAG, "SSE failure: ${t?.javaClass?.simpleName ?: "unknown"} http=${response?.code ?: -1}")
            if (running.get()) scheduleReconnect()
        }
    }

    /**
     * Коалесцирует всплеск SSE-событий в один sync: после последнего события
     * ждём SSE_DEBOUNCE_MS и запускаем ровно один requestImmediateSync. Мягче,
     * чем enqueue на каждое событие (часть которых WorkManager KEEP отбросил бы
     * во время идущего синка), и гарантирует sync после всплеска.
     */
    private fun requestSyncDebounced() {
        syncDebounceJob?.cancel()
        syncDebounceJob = scope.launch {
            delay(SSE_DEBOUNCE_MS)
            MatcheckSyncScheduler.requestImmediateSync(appContext)
        }
    }

    private fun scheduleReconnect() {
        // Не плодим параллельные реконнекты.
        if (reconnectJob?.isActive == true) return
        val attempt = reconnectAttempts
        reconnectAttempts++
        reconnectJob = scope.launch {
            val delayMs = reconnectDelayMs(attempt)
            Log.i(TAG, "SSE reconnect scheduled in ${delayMs}ms (attempt ${attempt + 1})")
            delay(delayMs)
            if (running.get()) openSource()
        }
    }

    /**
     * Экспоненциальный backoff с потолком: 1s → 2s → 5s → 15s → 30s → 60s.
     * Плюс джиттер 0–1с, чтобы при массовом обрыве планшеты не штормили сервер
     * синхронно. reconnectAttempts сбрасывается в 0 при успешном onOpen, так что
     * после восстановления связи следующий разрыв снова начинает с 1с.
     */
    private fun reconnectDelayMs(attempt: Int): Long {
        val base = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 5_000L
            3 -> 15_000L
            4 -> 30_000L
            else -> 60_000L
        }
        return base + Random.nextLong(0, 1_000L)
    }

    /**
     * «Разбудить» соединение: вызывается при возврате приложения в foreground
     * и при появлении сети. Если мы залогинены (running), но канал не на связи
     * (Disconnected/Connecting или ждёт в backoff-паузе), форсируем немедленный
     * реконнект, сбросив backoff. Если уже Connected — ничего не делаем
     * (watchdog следит за ping). Защита от параллельных соединений: отменяем
     * текущий reconnect-job и старый EventSource перед openSource.
     */
    fun wake() {
        if (!running.get()) return
        if (_state.value == ConnectionState.Connected) return
        Log.i(TAG, "SSE wake → immediate reconnect")
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        currentEventSource?.cancel()
        currentEventSource = null
        scope.launch { openSource() }
    }

    private suspend fun watchdog() {
        while (scope.isActive && running.get()) {
            delay(WATCHDOG_PERIOD_MS)
            val sincePingMs = System.currentTimeMillis() - lastPingAtMs.get()
            if (_state.value == ConnectionState.Connected && sincePingMs > PING_TIMEOUT_MS) {
                // Сервер молчит дольше 60 сек — рвём соединение принудительно,
                // дальше scheduleReconnect поднимет заново.
                Log.w(TAG, "SSE ping timeout (${sincePingMs}ms) → force reconnect")
                currentEventSource?.cancel()
                currentEventSource = null
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        }
    }

    enum class ConnectionState { Disconnected, Connecting, Connected }

    private companion object {
        const val TAG = "SseConn"
        const val PING_TIMEOUT_MS = 60_000L
        const val WATCHDOG_PERIOD_MS = 15_000L
        // Окно коалесцирования всплеска SSE-событий в один sync (1–3с из плана).
        const val SSE_DEBOUNCE_MS = 1_500L
    }
}
