package com.example.matcheckmobile.data.remote.sse

import android.content.Context
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
            _state.value = ConnectionState.Connected
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
                    // Дернуть фоновый sync — WorkManager сам сделает debounce
                    // через ExistingWorkPolicy.REPLACE.
                    MatcheckSyncScheduler.requestImmediateSync(appContext)
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
            if (running.get()) scheduleReconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            _state.value = ConnectionState.Disconnected
            if (running.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        // Не плодим параллельные реконнекты.
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val jitterMs = Random.nextLong(MIN_RECONNECT_MS, MAX_RECONNECT_MS + 1)
            delay(jitterMs)
            if (running.get()) openSource()
        }
    }

    private suspend fun watchdog() {
        while (scope.isActive && running.get()) {
            delay(WATCHDOG_PERIOD_MS)
            val sincePingMs = System.currentTimeMillis() - lastPingAtMs.get()
            if (_state.value == ConnectionState.Connected && sincePingMs > PING_TIMEOUT_MS) {
                // Сервер молчит дольше 60 сек — рвём соединение принудительно,
                // listener.onFailure запустит reconnect.
                currentEventSource?.cancel()
                currentEventSource = null
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        }
    }

    enum class ConnectionState { Disconnected, Connecting, Connected }

    private companion object {
        const val PING_TIMEOUT_MS = 60_000L
        const val WATCHDOG_PERIOD_MS = 15_000L
        const val MIN_RECONNECT_MS = 2_000L
        const val MAX_RECONNECT_MS = 10_000L
    }
}
