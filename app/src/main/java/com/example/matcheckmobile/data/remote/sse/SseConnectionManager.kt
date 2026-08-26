package com.example.matcheckmobile.data.remote.sse

import android.content.Context
import android.util.Log
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.remote.api.dto.SseEventPayload
import com.example.matcheckmobile.data.remote.net.CertificatePins
import com.example.matcheckmobile.data.remote.net.TokenRefreshCoordinator
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
 * - 401 → обновляем токен через общий [TokenRefreshCoordinator] и сразу
 *   переподключаемся. Прежде здесь стояло допущение «очередное reconnect
 *   возьмёт обновлённый access из TokenStorage», но обновлять его было некому:
 *   перехватчик висит на бизнес-клиенте, а этот — свой. Получался замкнутый
 *   круг: канал лёг → триггеров нет → циклов нет → токен никто не обновляет →
 *   канал снова 401. Размыкала его только периодика раз в 15 минут.
 *   Если сессии нет или сервер её отверг — активность обнуляем.
 *
 * Lifecycle: запускается на login / при холодном старте с сессией, останавливается
 * на logout. Между подключениями есть jitter, чтобы не штормить сервер при
 * массовых отключениях.
 */
class SseConnectionManager(
    private val baseUrl: String,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val sourceDocumentDao: RemoteSourceDocumentDao,
    private val appContext: Context,
    /**
     * Общий координатор обновления токена. Раньше канал не умел обновлять его
     * вовсе и на 401 стучался мёртвым токеном раз в минуту, пока кто-нибудь
     * другой случайно не обновит (замер 26.08: 137 устройств, до 843 секунд без
     * уведомлений). Теперь обновляем сами — через тот же замок, что и остальные,
     * иначе три точки ротировали бы refresh-токен наперегонки.
     */
    private val coordinator: TokenRefreshCoordinator,
    /** Тот же User-Agent, что у бизнес-клиента: без него планшеты неотличимы в логах сервера. */
    private val userAgent: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Без TokenAuthenticator: обновлением занимается [coordinator], а не
     * перехватчик, — поток здесь долгоживущий, и повторять запрос нечего.
     * readTimeout=0 для long-lived потока: иначе OkHttp закроет соединение
     * через 30 сек "idle".
     *
     * Пины — тот же набор, что у бизнес-клиента. Раньше их здесь не было вовсе,
     * то есть канал уведомлений ходил по непроверенной цепочке, пока весь
     * остальной трафик проверялся.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .certificatePinner(CertificatePins.build())
        .build()

    private val running = AtomicBoolean(false)
    private val lastPingAtMs = AtomicLong(0L)

    /**
     * Номер текущего соединения — им и определяется «свой ли колбэк».
     *
     * Сверять по ссылке на EventSource нельзя, и это не мелочь: `newEventSource()`
     * запускает соединение ДО того, как вернёт объект. Колбэк успевает прийти
     * раньше, чем нам есть что с ним сравнивать, — и был бы отброшен как чужой,
     * а соединение навсегда осталось бы в Connecting без переподключения.
     * Поколение же проставляется ДО создания источника, поэтому зазора нет.
     *
     * Обратный случай тоже закрыт: у отменённого источника колбэки продолжают
     * приходить, и без сверки старый onFailure сбивал бы состояние уже поднятого
     * нового соединения.
     */
    @Volatile
    private var currentGeneration: Long = 0
    private val generationSeq = AtomicLong(0)

    /** Живой источник — только чтобы его отменить. */
    @Volatile
    private var currentSource: EventSource? = null

    /**
     * Токен, ПОЛУЧЕННЫЙ последним обновлением. Если 401 придёт уже на него,
     * второй раз подряд не обновляемся — иначе начнём бесконечную ротацию,
     * которую сервер сочтёт переиспользованием и погасит сессию.
     *
     * Хранится именно новый токен, а не тот, что получил отказ: запомни мы
     * старый, следующая ротация запустилась бы немедленно. Сбрасывается при
     * успешном onOpen — иначе один неудачный обмен запер бы канал в backoff
     * навсегда.
     */
    @Volatile
    private var refreshedTo: String? = null

    @Volatile
    private var refreshJob: Job? = null

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
        refreshJob?.cancel()
        refreshJob = null
        refreshedTo = null
        currentGeneration = generationSeq.incrementAndGet() // осиротить все колбэки
        currentSource?.cancel()
        currentSource = null
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Открыть поток, предварительно раздобыв пригодный токен.
     *
     * Проверка ДО подключения принципиальна: маршрут `/api/v1/events` авторизует
     * один раз, в preHandler, и живой поток спокойно переживает истечение токена.
     * Значит 401 возникает только при переподключении — ровно в тот момент,
     * когда канал и нужен. Открывать соединение заведомо истёкшим токеном
     * означало бы подарить серверу гарантированный отказ и потратить попытку.
     */
    private suspend fun openSource() {
        if (!running.get()) return
        val token = when (val outcome = coordinator.obtainFresh(staleToken = null, path = EVENTS_PATH)) {
            is TokenRefreshCoordinator.Outcome.Fresh -> outcome.accessToken

            // Сессии нет или сервер её отверг: канал поднимать не для кого.
            // Координатор уже сделал всё, что полагается (для Invalid — погасил
            // и уведомил однократно; для NoSession гасить нечего и события нет).
            TokenRefreshCoordinator.Outcome.NoSession,
            TokenRefreshCoordinator.Outcome.Invalid -> {
                halt()
                return
            }

            // Сессию сменили, пока мы собирались. Не гадаем, чья она теперь, —
            // обычный reconnect подхватит актуальную.
            TokenRefreshCoordinator.Outcome.SessionChanged -> {
                scheduleReconnect()
                return
            }

            // Сеть недоступна — ведём себя как раньше, ждём и пробуем снова.
            TokenRefreshCoordinator.Outcome.NetworkError -> {
                scheduleReconnect()
                return
            }
        }
        if (!running.get()) return
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v1/events")
            .header("Authorization", "Bearer $token")
            .header("X-Client-Type", "mobile")
            .header("User-Agent", userAgent)
            .build()
        _state.value = ConnectionState.Connecting
        // Поколение и слушатель заводятся ДО newEventSource: соединение стартует
        // внутри вызова, и колбэк может прийти раньше, чем вызов вернётся.
        val generation = generationSeq.incrementAndGet()
        currentGeneration = generation
        currentSource = EventSources.createFactory(client)
            .newEventSource(request, SseListener(generation, token))
    }

    /** Остановка без попыток восстановления: поднимать канал не для кого. */
    private fun halt() {
        reconnectJob?.cancel()
        reconnectJob = null
        currentGeneration = generationSeq.incrementAndGet()
        currentSource?.cancel()
        currentSource = null
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Слушатель одного КОНКРЕТНОГО соединения: помнит своё поколение и токен,
     * которым это соединение открыто. Общий слушатель на все соединения не
     * годился бы — по 401 надо понять, какой именно токен оказался негодным.
     */
    private inner class SseListener(
        private val generation: Long,
        private val token: String,
    ) : EventSourceListener() {

        private fun isMine(): Boolean = generation == currentGeneration

        override fun onOpen(eventSource: EventSource, response: Response) {
            if (!isMine()) return
            lastPingAtMs.set(System.currentTimeMillis())
            // Обмен удался — снимаем защиту от повторного обновления. Без этого
            // один неудачный refresh запер бы канал в backoff до перезапуска.
            refreshedTo = null
            reconnectAttempts = 0 // успешное подключение — сбрасываем backoff
            _state.value = ConnectionState.Connected
            Log.i(TAG, "SSE connected")
            // После (ре)подключения догоняем изменения, пропущенные за время
            // разрыва канала. Лавины не будет: запросы ставятся цепочкой
            // (APPEND_OR_REPLACE), а лишние звенья схлопывает счётчик поколений
            // в MatcheckSyncWorker — цикл выполнит только первое.
            Log.i(TAG, "sync triggered by SSE (re)connect")
            MatcheckSyncScheduler.requestImmediateSync(appContext)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (!isMine()) return
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
            if (!isMine()) return
            currentSource = null
            _state.value = ConnectionState.Disconnected
            Log.i(TAG, "SSE closed")
            if (running.get()) scheduleReconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            // Колбэк отменённого соединения приходит и после cancel(). Без этой
            // сверки старый onFailure погасил бы состояние уже поднятого нового.
            if (!isMine()) return
            currentSource = null
            _state.value = ConnectionState.Disconnected
            // Без персональных данных: только тип ошибки + HTTP-код.
            Log.w(TAG, "SSE failure: ${t?.javaClass?.simpleName ?: "unknown"} http=${response?.code ?: -1}")
            if (!running.get()) return

            if (shouldRefreshOnUnauthorized(response?.code, token, refreshedTo)) {
                refreshAndReconnect(token)
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Один обмен «обновить токен и переподключиться».
     *
     * Обычный [scheduleReconnect] в это время НЕ ставится: иначе на один отказ
     * пришлось бы два переподключения — одно по таймеру со старым токеном,
     * другое после обновления.
     */
    private fun refreshAndReconnect(staleToken: String) {
        if (refreshJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectJob = null
        refreshJob = scope.launch {
            when (val outcome = coordinator.obtainFresh(staleToken, EVENTS_PATH)) {
                is TokenRefreshCoordinator.Outcome.Fresh -> {
                    // Защиту вешаем на НОВЫЙ токен: если 401 придёт уже на него,
                    // второго обновления не будет.
                    refreshedTo = outcome.accessToken
                    reconnectAttempts = 0
                    if (running.get()) openSource()
                }

                TokenRefreshCoordinator.Outcome.NoSession,
                TokenRefreshCoordinator.Outcome.Invalid -> halt()

                TokenRefreshCoordinator.Outcome.SessionChanged,
                TokenRefreshCoordinator.Outcome.NetworkError -> scheduleReconnect()
            }
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
        currentGeneration = generationSeq.incrementAndGet()
        currentSource?.cancel()
        currentSource = null
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
                currentGeneration = generationSeq.incrementAndGet()
                currentSource?.cancel()
                currentSource = null
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
        /** Только для журнала обновления токена — по нему видно, что цепочку начал SSE. */
        const val EVENTS_PATH = "/api/v1/events"
    }
}

/**
 * Обновлять ли токен в ответ на отказ соединения.
 *
 * Вынесено из класса, потому что здесь легко ошибиться незаметно, а внутри
 * [SseConnectionManager] это не проверить: он тянет OkHttp, планировщик и DAO.
 *
 * Правило одно, и вся его суть в третьем аргументе. Защита от повторного
 * обновления привязана к токену, КОТОРЫЙ ОБНОВЛЕНИЕ ВЕРНУЛО, а не к тому, что
 * получил отказ. Запомни мы отказавший, следующая ротация запустилась бы сразу
 * же — а две ротации подряд сервер считает переиспользованием refresh-токена и
 * гасит сессию. Булев флаг тоже не годится: единожды взведённый, он оставил бы
 * канал в backoff навсегда.
 *
 * @param failedToken токен, с которым было создано отказавшее соединение.
 * @param alreadyRefreshedTo токен, полученный прошлым обновлением; null — обновления не было.
 */
internal fun shouldRefreshOnUnauthorized(
    httpCode: Int?,
    failedToken: String?,
    alreadyRefreshedTo: String?,
): Boolean {
    if (httpCode != 401) return false
    if (failedToken == null) return false
    return failedToken != alreadyRefreshedTo
}
