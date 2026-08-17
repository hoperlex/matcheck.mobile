package com.example.matcheckmobile.data.repository

import android.util.Log
import com.example.matcheckmobile.data.local.dao.RemoteCounterpartyDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteMaterialDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSiteDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.local.dao.RemoteStatusDao
import com.example.matcheckmobile.data.local.dao.RemoteUnitDao
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toUpsertRequest
import com.example.matcheckmobile.data.remote.api.DeliveriesApi
import com.example.matcheckmobile.data.remote.api.ShipmentsApi
import com.example.matcheckmobile.data.remote.api.SourceDocumentsApi
import com.example.matcheckmobile.data.remote.api.SyncApi
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.ReconcileItemDto
import com.example.matcheckmobile.data.remote.api.dto.ReconcileRequestDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.SyncDeltaResponse
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.domain.StatusPolicy
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

/**
 * Дельта-синхронизация (pull). Push (POST upsert + OCC) сделаем в Этапе 3.
 *
 * Алгоритм по MOBILE_API.md «Дельта-синхронизация»:
 *
 * 1. since = stored_cursor (null → initial-sync с windowDays = 90)
 * 2. GET /sync?since=...&windowDays=... → response
 * 3. upsert: statuses → references → sourceDocuments → deliveries → shipments
 * 4. удалить локальные записи из deletedIds.{deliveries,shipments,sourceDocuments}
 * 5. если хоть один массив length == limit → since = response.cursor, GOTO 2
 * 6. иначе stored_cursor = response.cursor
 */
class SyncRepository(
    private val syncApi: SyncApi,
    private val deliveriesApi: DeliveriesApi,
    private val shipmentsApi: ShipmentsApi,
    private val sourceDocumentsApi: SourceDocumentsApi,
    private val deviceSettings: DeviceSettings,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val counterpartyDao: RemoteCounterpartyDao,
    private val materialDao: RemoteMaterialDao,
    private val siteDao: RemoteSiteDao,
    private val statusDao: RemoteStatusDao,
    private val unitDao: RemoteUnitDao,
    private val sourceDocumentDao: RemoteSourceDocumentDao,
    private val mutationDao: MutationDao,
    /**
     * Запись серверных агрегатов идёт только через него: он не даёт затереть
     * локальное время подтверждения, пока оно ещё не доехало до сервера
     * (см. PendingAwareRemoteWriter).
     */
    private val remoteWriter: PendingAwareRemoteWriter,
    private val mutationProcessor: MutationProcessor,
    private val photoUploadProcessor: PhotoUploadProcessor,
    /**
     * Авто-разрешение терминальных OCC-конфликтов (server-win) — чинит
     * «запись навсегда застряла на Этапе 2». Приёмки и отгрузки.
     */
    private val terminalConflictResolver: TerminalConflictResolver,
    /**
     * Side-effect: сразу после успешного pull тихо подтягивает с /me актуальный
     * user.siteId инспектора и обновляет tokenStorage. Если null —
     * sync работает по-старому (без обновления siteId). Передаётся
     * через лямбду, чтобы не плодить зависимость на AuthRepository.
     * Любые ошибки внутри лямбды должны проглатываться её реализацией —
     * SyncRepository вызывает её best-effort и не ждёт результата.
     */
    private val onAfterPullRefresh: (suspend () -> Unit)? = null,
    /**
     * Side-effect в самом начале цикла: доделать незавершённый частичный сброс
     * snapshot после смены объекта. Долг персистентный, поэтому его надо
     * подхватывать на каждом синке — процесс мог быть убит в прошлый раз.
     * Реализация обязана глотать свои ошибки.
     */
    private val onBeforeSync: (suspend () -> Unit)? = null,
    /**
     * Side-effect после успешной загрузки фото: тяжёлые последствия смены
     * объекта (сброс server-snapshot и перезапуск sync). Вынесено отдельно
     * от [onAfterPullRefresh] намеренно — сам siteId обновляем рано, чтобы
     * сбой заливки фото не оставлял планшет со старым объектом, а вот чистку
     * снапшота делаем только после того, как незалитые фото ушли на сервер.
     */
    private val onAfterPhotoUpload: (suspend () -> Unit)? = null,
    /**
     * Объект текущей сессии (`TokenStorage.effectiveSiteId`). Нужен M4b
     * push-recovery: переотправлять можно только записи СВОЕГО объекта, иначе
     * планшет с чужими остатками перетаскивает их к себе (инцидент 2026-07).
     */
    private val currentSiteId: () -> String? = { null },
) {

    private val pullMutex = Mutex()
    private val syncMutex = Mutex()

    // Throttle для reconcile: не чаще раза в минуту (см. reconcileOnce) — частый
    // backstop к best-effort SSE/периодике. In-memory — после рестарта процесса
    // первый reconcile пройдёт сразу, это допустимо.
    @Volatile
    private var lastReconcileAtMs = 0L

    // Сериализация payload мутации тем же форматом, что DeliveryRepository/
    // ShipmentRepository.upsert — MutationProcessor его прочитает идентично.
    private val resendJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Прокачивает все страницы дельты, пока сервер не вернёт «короткий» ответ.
     * Безопасно вызывать конкурентно — лишние вызовы дожидаются текущего.
     */
    suspend fun pullDelta(initialWindowDays: Int = DEFAULT_INITIAL_WINDOW_DAYS): Result<SyncSummary> {
        return pullMutex.withLock { runCatching { pullAllPages(initialWindowDays) } }
            .onSuccess { summary -> _state.value = _state.value.copy(lastError = null, lastSuccessSummary = summary) }
            .onFailure { error -> _state.value = _state.value.copy(lastError = error.message ?: "sync failed") }
    }

    /** Прокачать pending-мутации на сервер (200/409/4xx/5xx обработка внутри). */
    suspend fun pushPending(): Result<MutationProcessor.PushResult> = runCatching {
        mutationProcessor.processAll()
    }

    /**
     * Выполняет [block] под тем же мьютексом, что и [syncOnce] — то есть
     * гарантированно НЕ параллельно с push/pull/загрузкой фото.
     *
     * Нужен при смене аккаунта: очистка локальной базы обязана дождаться
     * текущего цикла синхронизации, иначе он допишет в уже очищенную базу
     * данные прошлого пользователя (или отправит их под новым токеном).
     */
    suspend fun <T> runExclusively(block: suspend () -> T): T = syncMutex.withLock { block() }

    /**
     * Снимает минутный throttle reconcile'а.
     *
     * Throttle живёт в памяти процесса и не знает о смене контекста. После
     * смены аккаунта или объекта база начинается с нуля, и докачка хвоста
     * нужна немедленно — иначе reconcile, отработавший минуту назад для
     * ПРОШЛОГО объекта, откладывал бы её на целую минуту.
     */
    fun resetReconcileThrottle() {
        lastReconcileAtMs = 0L
    }

    /**
     * Полный цикл: push мутаций → pull дельты → push фото. Порядок важен:
     * фото можно грузить только после того, как parent (delivery/shipment)
     * ушла на сервер и получила version > 0. Поэтому photo upload — после
     * push mutations и pull (на pull прилетит обновлённый version).
     */
    suspend fun syncOnce(initialWindowDays: Int = DEFAULT_INITIAL_WINDOW_DAYS): Result<SyncSummary> =
        withCycleTimeout(
            timeoutMs = CYCLE_TIMEOUT_MS,
            mutex = syncMutex,
            onTimeout = { elapsed -> Log.w(TAG, "sync cycle прерван по таймауту через ${elapsed}мс") },
            block = { runCycle(initialWindowDays) },
        )
            .onSuccess { summary -> _state.value = _state.value.copy(lastError = null, lastSuccessSummary = summary) }
            .onFailure { error -> _state.value = _state.value.copy(lastError = error.message ?: "sync failed") }

    /**
     * Тело цикла. Мьютекс, потолок по времени и семантику отмены держит
     * [withCycleTimeout] — здесь только сама работа.
     */
    private suspend fun runCycle(initialWindowDays: Int): Result<SyncSummary> {
        return runCatching {
            // Незавершённый сброс после смены объекта — до всего остального:
            // он чистит курсор, и следующий pull должен пойти уже как initial.
            runCatching { onBeforeSync?.invoke() }
            mutationProcessor.processAll()
            // Снять терминальные конфликты БЕЗ сети (ветка B / накопленное
            // равно-версионное состояние) — до pull, чтобы отрабатывало даже
            // когда /sync недоступен. Best-effort: сбой не ломает основной sync.
            runCatching { terminalConflictResolver.sweepLocalTerminal() }
            val summary = pullAllPages(initialWindowDays)
            // Best-effort обновление siteId инспектора — СРАЗУ после pull.
            // Раньше вызов стоял после загрузки фото, и её падение (S3 5xx,
            // обрыв сети) прерывало syncOnce до обновления siteId: планшет
            // продолжал жить со старым объектом — неверный штамп на фото и
            // фильтры списков. Лямбда сама обязана глотать исключения.
            runCatching { onAfterPullRefresh?.invoke() }
            photoUploadProcessor.processAll()
            // Тяжёлые последствия смены объекта (сброс snapshot) — только
            // после того, как незалитые фото ушли: reset удаляет приёмки и
            // отгрузки вместе с их фото-очередью.
            runCatching { onAfterPhotoUpload?.invoke() }
            // Фоновая сверка с сервером (throttled внутри). Best-effort: любая
            // её ошибка не должна влиять на результат основного sync.
            runCatching { reconcileOnce() }
            summary
        }
    }

    /**
     * Read/repair-from-server сверка (M4a). Собирает локальные id+version,
     * спрашивает сервер о расхождениях и ДОКАЧИВАЕТ недостающее/устаревшее через
     * detail-endpoints. НИЧЕГО локально не удаляет и не переотправляет:
     * missingOnServer (push-потеря) здесь только логируется — его обработка
     * будет отдельным шагом M4b. conflictPending-записи не трогаются.
     * Throttle: не чаще RECONCILE_INTERVAL_MS. Любая ошибка — тихо, не ломая sync.
     */
    suspend fun reconcileOnce() {
        val now = System.currentTimeMillis()
        if (now - lastReconcileAtMs < RECONCILE_INTERVAL_MS) return

        val localDel = deliveryDao.listReconcileVersions()
        val localShip = shipmentDao.listReconcileVersions()
        val localSd = sourceDocumentDao.listReconcileVersions()
        Log.i(TAG, "reconcile started: local del=${localDel.size} ship=${localShip.size} sd=${localSd.size}")

        val resp = runCatching {
            syncApi.reconcile(
                ReconcileRequestDto(
                    deliveries = localDel.map { ReconcileItemDto(it.id, it.version) },
                    shipments = localShip.map { ReconcileItemDto(it.id, it.version) },
                    sourceDocuments = localSd.map { ReconcileItemDto(it.id, it.version) },
                    // Та же capability, что и в дельте. Без неё сверка отбирала бы
                    // документы прежним предикатом и возвращала обратно то, что
                    // дельта только что скрыла tombstone'ом.
                    capabilities = SOURCE_GROUPS_CAPABILITY,
                ),
            )
        }.getOrElse { e ->
            // Нет сети / 401 / API error — тихо завершаем, не ломая обычный sync.
            Log.i(TAG, "reconcile skipped: ${e.javaClass.simpleName}")
            return
        }
        // Засчитываем throttle только при успешном вызове, чтобы временная ошибка
        // не «съедала» часовое окно.
        lastReconcileAtMs = now

        // M4b: переотправляем missingOnServer для deliveries/shipments (push-recovery —
        // «на планшете есть, на сервере нет»). sourceDocuments НЕ трогаем
        // (attachments/upload рискованнее — отдельный этап).
        val reqDel = requeueMissingDeliveries(resp.deliveries.missingOnServer)
        val reqShip = requeueMissingShipments(resp.shipments.missingOnServer)
        Log.i(
            TAG,
            "reconcile missingOnServer: del=${resp.deliveries.missingOnServer.size}(requeued=$reqDel) " +
                "ship=${resp.shipments.missingOnServer.size}(requeued=$reqShip) " +
                "sd=${resp.sourceDocuments.missingOnServer.size}(ignored)",
        )
        Log.i(
            TAG,
            "reconcile missing/stale: " +
                "del m=${resp.deliveries.missingOnClient.size} s=${resp.deliveries.staleOnClient.size} | " +
                "ship m=${resp.shipments.missingOnClient.size} s=${resp.shipments.staleOnClient.size} | " +
                "sd m=${resp.sourceDocuments.missingOnClient.size} s=${resp.sourceDocuments.staleOnClient.size}",
        )

        reconcileDeliveries(
            resp.deliveries.missingOnClient.map { it.id } + resp.deliveries.staleOnClient.map { it.id },
        )
        reconcileShipments(
            resp.shipments.missingOnClient.map { it.id } + resp.shipments.staleOnClient.map { it.id },
        )
        reconcileSourceDocs(
            resp.sourceDocuments.missingOnClient.map { it.id } + resp.sourceDocuments.staleOnClient.map { it.id },
        )
    }

    private suspend fun reconcileDeliveries(ids: List<String>) {
        if (ids.isEmpty()) return
        // Решение (пропустить конфликт / server-win / обычная запись) принимается
        // ЦЕЛИКОМ внутри транзакции по свежему чтению entity — предвычисленный
        // список conflictPending мог устареть за время сетевого GET и дать сироту.
        var ok = 0
        var skipped = 0
        var fail = 0
        for (id in ids.distinct()) {
            runCatching {
                val dto = deliveriesApi.get(id) // сеть — ВНЕ транзакции
                terminalConflictResolver.applyReconciled(
                    id = id,
                    isServerTerminal = StatusPolicy.isTerminal(dto.status.code),
                ) {
                    remoteWriter.saveDelivery(
                        delivery = dto.toEntity(),
                        items = dto.items.map { it.toEntity(dto.id) },
                        photos = dto.photos.map { it.toEntity(dto.id) },
                    )
                }
            }.onSuccess { result ->
                when (result) {
                    is TerminalConflictResolver.Outcome.Skipped -> skipped++
                    is TerminalConflictResolver.Outcome.Applied -> ok++
                }
            }.onFailure { fail++ }
        }
        Log.i(TAG, "reconcile detail deliveries: ok=$ok skipped=$skipped fail=$fail")
    }

    private suspend fun reconcileShipments(ids: List<String>) {
        if (ids.isEmpty()) return
        // Симметрично приёмкам: решение принимается внутри транзакции по свежему
        // чтению. Раньше конфликтные выезды просто пропускались (skip-множество)
        // и оставались висеть на 2 Этапе навсегда.
        var ok = 0
        var skipped = 0
        var fail = 0
        for (id in ids.distinct()) {
            runCatching {
                val dto = shipmentsApi.get(id) // сеть — ВНЕ транзакции
                terminalConflictResolver.applyReconciled(
                    id = id,
                    isServerTerminal = StatusPolicy.isTerminal(dto.status.code),
                    entityType = TerminalConflictResolver.ENTITY_SHIPMENT,
                ) {
                    remoteWriter.saveShipment(
                        shipment = dto.toEntity(),
                        items = dto.items.map { it.toEntity(dto.id) },
                        photos = dto.photos.map { it.toEntity(dto.id) },
                    )
                }
            }.onSuccess { result ->
                when (result) {
                    is TerminalConflictResolver.Outcome.Skipped -> skipped++
                    is TerminalConflictResolver.Outcome.Applied -> ok++
                }
            }.onFailure { fail++ }
        }
        Log.i(TAG, "reconcile detail shipments: ok=$ok skipped=$skipped fail=$fail")
    }

    private suspend fun reconcileSourceDocs(ids: List<String>) {
        if (ids.isEmpty()) return
        var ok = 0
        var fail = 0
        for (id in ids.distinct()) {
            runCatching {
                val dto = sourceDocumentsApi.get(id)
                sourceDocumentDao.saveAggregate(
                    doc = dto.toEntity(),
                    items = dto.items.map { it.toEntity(dto.id) },
                    attachments = dto.attachments.map { it.toEntity(dto.id) },
                )
            }.onSuccess { ok++ }.onFailure { fail++ }
        }
        Log.i(TAG, "reconcile detail sourceDocs: ok=$ok fail=$fail")
    }

    /**
     * M4b push-recovery: для каждой приёмки из missingOnServer (есть локально,
     * нет на сервере) безопасно пересоздаёт upsert-мутацию из локальной записи,
     * чтобы операция снова ушла на сервер. НИКОГДА не удаляет локальную запись и
     * не трогает существующие мутации. Skip (только лог), если: записи нет;
     * conflictPending; upsert-мутация уже в очереди (idempotency); payload не
     * собрался; запись принадлежит ЧУЖОМУ объекту. Ошибка одной записи не
     * прерывает остальные.
     *
     * Фильтр по объекту — ключевая защита: reconcile считает «пропавшим на
     * сервере» всё, чего нет в scope текущего пользователя, поэтому остатки
     * прошлого аккаунта попадали в переотправку и физически переезжали на
     * объект нового (инцидент 2026-07). Сравниваем с effectiveSiteId
     * (last-known-good), чтобы транзиентно пустой siteId не блокировал
     * переотправку СВОИХ записей.
     */
    private suspend fun requeueMissingDeliveries(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        var requeued = 0
        var skipMissing = 0
        var skipConflict = 0
        var skipExisting = 0
        var skipInvalid = 0
        var skipForeign = 0
        val ownSiteId = currentSiteId()
        for (id in ids.distinct()) {
            val entity = deliveryDao.findById(id)
            if (entity == null) {
                skipMissing++; continue // нечего отправлять, ничего не создаём
            }
            if (ownSiteId.isNullOrBlank() || entity.siteId.isBlank() || entity.siteId != ownSiteId) {
                skipForeign++; continue // чужой объект (или свой неизвестен) — не переотправляем
            }
            if (entity.conflictPending) {
                skipConflict++; continue // не трогаем запись в конфликте
            }
            if (mutationDao.findFor("delivery", id).any { it.operation == "upsert" }) {
                skipExisting++; continue // idempotency: дубль не плодим
            }
            val ok = runCatching {
                val items = deliveryDao.findItemsByDelivery(id)
                val request = entity.toUpsertRequest(items)
                mutationDao.upsert(
                    MutationEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "delivery",
                        operation = "upsert",
                        entityId = id,
                        baseVersion = entity.version,
                        payloadJson = resendJson.encodeToString(DeliveryUpsertRequest.serializer(), request),
                        attempts = 0,
                        nextAttemptAt = null,
                        lastError = null,
                        conflictPending = false,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.isSuccess
            if (ok) requeued++ else skipInvalid++ // payload/insert упал — skip, продолжаем
        }
        Log.i(
            TAG,
            "requeue deliveries: requeued=$requeued missing=$skipMissing conflict=$skipConflict " +
                "existing=$skipExisting invalid=$skipInvalid foreign=$skipForeign",
        )
        return requeued
    }

    /** Симметрично requeueMissingDeliveries для отгрузок (включая фильтр по объекту). */
    private suspend fun requeueMissingShipments(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        var requeued = 0
        var skipMissing = 0
        var skipConflict = 0
        var skipExisting = 0
        var skipInvalid = 0
        var skipForeign = 0
        val ownSiteId = currentSiteId()
        for (id in ids.distinct()) {
            val entity = shipmentDao.findById(id)
            if (entity == null) {
                skipMissing++; continue
            }
            if (ownSiteId.isNullOrBlank() || entity.siteId.isBlank() || entity.siteId != ownSiteId) {
                skipForeign++; continue
            }
            if (entity.conflictPending) {
                skipConflict++; continue
            }
            if (mutationDao.findFor("shipment", id).any { it.operation == "upsert" }) {
                skipExisting++; continue
            }
            val ok = runCatching {
                val items = shipmentDao.findItemsByShipment(id)
                val request = entity.toUpsertRequest(items)
                mutationDao.upsert(
                    MutationEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "shipment",
                        operation = "upsert",
                        entityId = id,
                        baseVersion = entity.version,
                        payloadJson = resendJson.encodeToString(ShipmentUpsertRequest.serializer(), request),
                        attempts = 0,
                        nextAttemptAt = null,
                        lastError = null,
                        conflictPending = false,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.isSuccess
            if (ok) requeued++ else skipInvalid++
        }
        Log.i(
            TAG,
            "requeue shipments: requeued=$requeued missing=$skipMissing conflict=$skipConflict " +
                "existing=$skipExisting invalid=$skipInvalid foreign=$skipForeign",
        )
        return requeued
    }

    /** Только push pending photos — для триггера после capture без полного syncOnce. */
    suspend fun pushPendingPhotos(): Result<PhotoUploadProcessor.PhotoUploadResult> = runCatching {
        photoUploadProcessor.processAll()
    }

    private suspend fun pullAllPages(initialWindowDays: Int): SyncSummary {
        _state.value = _state.value.copy(isRunning = true)
        try {
            // Курсор, с которого начат проход. В групповом режиме он остаётся
            // неизменным до конца обхода: позицию внутри снимка несёт pageToken,
            // а `since` задаёт сам снимок. Меняя since между страницами, мы
            // сдвинули бы снимок под собой.
            var cursor: String? = deviceSettings.readSyncCursor()
            val isInitial = cursor == null
            var pages = 0
            var pageToken: String? = null
            val totals = SyncSummary.Builder()
            while (true) {
                val response = syncApi.delta(
                    since = cursor,
                    // Окно нужно только самому первому запросу initial-sync:
                    // страницы внутри того же прохода его не переспрашивают.
                    windowDays = if (cursor == null && pageToken == null) initialWindowDays else null,
                    capabilities = SOURCE_GROUPS_CAPABILITY,
                    pageToken = pageToken,
                )
                applyResponse(response)
                pages++
                totals.addPage(response)

                // Правило обхода вынесено в decidePageStep и покрыто тестом:
                // сдвиг курсора в середине снимка стоит потерянного хвоста.
                val step = decidePageStep(response.nextPageToken, hasMorePages(response))
                pageToken = step.pageToken
                if (step.commitCursor) {
                    val nextCursor = response.cursor
                    deviceSettings.setSyncCursor(nextCursor)
                    cursor = nextCursor
                }
                if (step.done) break
            }
            return totals.build(pages = pages, isInitial = isInitial)
        } finally {
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    private suspend fun applyResponse(r: SyncDeltaResponse) {
        if (r.statuses.isNotEmpty()) {
            statusDao.upsertAll(r.statuses.map { it.toEntity() })
        }
        if (r.counterparties.isNotEmpty()) {
            counterpartyDao.upsertAll(r.counterparties.map { it.toEntity() })
        }
        if (r.materials.isNotEmpty()) {
            materialDao.upsertAll(r.materials.map { it.toEntity() })
        }
        if (r.sites.isNotEmpty()) {
            siteDao.upsertAll(r.sites.map { it.toEntity() })
        }
        if (r.units.isNotEmpty()) {
            // Справочник единиц измерения для дропдауна «Ед.» в модалке
            // материалов. Меняется редко (~20 строк), отдаётся целиком —
            // дельта-фильтр не нужен. См. серверный sync.ts.
            unitDao.upsertAll(r.units.map { it.toEntity() })
        }
        for (sd in r.sourceDocuments) {
            sourceDocumentDao.saveAggregate(
                doc = sd.toEntity(),
                items = sd.items.map { it.toEntity(sd.id) },
                attachments = sd.attachments.map { it.toEntity(sd.id) },
            )
        }

        // Сущности с активным conflictPending=true НЕ перезаписываем — иначе
        // потеряем server-snapshot, который UI ждёт для резолюции.
        val skipDeliveries = deliveryDao.listConflictPendingIds().toSet()
        for (d in r.deliveries) {
            if (d.id in skipDeliveries) continue
            remoteWriter.saveDelivery(
                delivery = d.toEntity(),
                items = d.items.map { it.toEntity(d.id) },
                photos = d.photos.map { it.toEntity(d.id) },
            )
        }
        val skipShipments = shipmentDao.listConflictPendingIds().toSet()
        for (sh in r.shipments) {
            if (sh.id in skipShipments) continue
            remoteWriter.saveShipment(
                shipment = sh.toEntity(),
                items = sh.items.map { it.toEntity(sh.id) },
                photos = sh.photos.map { it.toEntity(sh.id) },
            )
        }
        if (r.deletedIds.deliveries.isNotEmpty()) deliveryDao.deleteByIds(r.deletedIds.deliveries)
        if (r.deletedIds.shipments.isNotEmpty()) shipmentDao.deleteByIds(r.deletedIds.shipments)
        if (r.deletedIds.sourceDocuments.isNotEmpty()) sourceDocumentDao.deleteByIds(r.deletedIds.sourceDocuments)
    }

    /** Лимиты на запрос — см. MOBILE_API.md. Если массив пришёл «полным» — есть ещё. */
    private fun hasMorePages(r: SyncDeltaResponse): Boolean {
        return r.counterparties.size == LIMIT_500 ||
            r.materials.size == LIMIT_500 ||
            r.sites.size == LIMIT_500 ||
            r.deliveries.size == LIMIT_500 ||
            r.shipments.size == LIMIT_500 ||
            r.sourceDocuments.size == LIMIT_SOURCE_DOCS
    }

    data class SyncState(
        val isRunning: Boolean = false,
        val lastError: String? = null,
        val lastSuccessSummary: SyncSummary? = null,
    )

    data class SyncSummary(
        val pages: Int,
        val isInitial: Boolean,
        val deliveries: Int,
        val shipments: Int,
        val sourceDocuments: Int,
        val counterparties: Int,
        val materials: Int,
        val sites: Int,
        val statuses: Int,
        val deletedDeliveries: Int,
        val deletedShipments: Int,
        val deletedSourceDocuments: Int,
    ) {
        class Builder {
            private var deliveries = 0
            private var shipments = 0
            private var sourceDocuments = 0
            private var counterparties = 0
            private var materials = 0
            private var sites = 0
            private var statuses = 0
            private var deletedDeliveries = 0
            private var deletedShipments = 0
            private var deletedSourceDocuments = 0

            fun addPage(r: SyncDeltaResponse) {
                deliveries += r.deliveries.size
                shipments += r.shipments.size
                sourceDocuments += r.sourceDocuments.size
                counterparties += r.counterparties.size
                materials += r.materials.size
                sites += r.sites.size
                statuses += r.statuses.size
                deletedDeliveries += r.deletedIds.deliveries.size
                deletedShipments += r.deletedIds.shipments.size
                deletedSourceDocuments += r.deletedIds.sourceDocuments.size
            }

            fun build(pages: Int, isInitial: Boolean) = SyncSummary(
                pages = pages,
                isInitial = isInitial,
                deliveries = deliveries,
                shipments = shipments,
                sourceDocuments = sourceDocuments,
                counterparties = counterparties,
                materials = materials,
                sites = sites,
                statuses = statuses,
                deletedDeliveries = deletedDeliveries,
                deletedShipments = deletedShipments,
                deletedSourceDocuments = deletedSourceDocuments,
            )
        }
    }

    private companion object {
        const val DEFAULT_INITIAL_WINDOW_DAYS = 90

        /**
         * Потолок на один цикл синхронизации.
         *
         * Восемь минут — заведомо меньше системного лимита WorkManager в 10
         * минут: ресурсы надо освобождать кооперативно, до принудительной
         * остановки воркера. Смысл потолка в том, что `syncOnce` держит
         * sync-мьютекс целиком, и зависший внутри вызов (исторически — PUT фото
         * в S3 без `callTimeout`) блокировал ВСЕ последующие синхронизации до
         * перезапуска процесса.
         */
        const val CYCLE_TIMEOUT_MS = 8 * 60 * 1000L
        // Должны совпадать с лимитами сервера (matcheck apps/api/src/routes/sync.ts):
        // deliveries/shipments — 500, sourceDocuments — 1000. Иначе hasMorePages
        // неверно детектит «полную страницу». MUST MATCH SERVER.
        const val LIMIT_500 = 500
        const val LIMIT_SOURCE_DOCS = 1000

        /**
         * Чем клиент заявляет серверу поддержку группового протокола.
         *
         * Пока строка не уходит в запрос, сервер ВСЕГДА отвечает по-старому:
         * машина из нескольких УПД приезжает отдельными документами, скрытие
         * необработанного не применяется, keyset-пагинация не включается. Это
         * третье и обязательное условие в серверном group-mode.ts — двух
         * остальных (флага и списка объектов) недостаточно.
         *
         * Заявлять её можно ровно потому, что ниже реализован обход страниц по
         * `nextPageToken` и курсор сдвигается только после последней страницы.
         * Без этого capability означала бы потерю хвоста синхронизации.
         *
         * MUST MATCH SERVER: SOURCE_GROUPS_CAPABILITY в
         * apps/api/src/domain/groups/group-mode.ts.
         */
        const val SOURCE_GROUPS_CAPABILITY = "source_groups_v1"
        const val TAG = "SyncReconcile"
        // Частый backstop к best-effort SSE/периодике. Win-условие (свежая filled
        // на другом планшете) даёт сам pull; reconcile лишь добирает пропущенное.
        // 60с + WorkManager KEEP-дедуп гасят спам при активной навигации.
        const val RECONCILE_INTERVAL_MS = 60 * 1000L // не чаще раза в минуту
    }
}

/** Локальный sanity-wrapper для исключений сети vs HTTP (не используется внутри, но удобен снаружи). */
fun Throwable.isTransient(): Boolean = this is IOException || (this is HttpException && code() in 500..599)

/**
 * Цикл синхронизации не уложился в отведённое время и был прерван.
 *
 * Намеренно НЕ наследует IOException: это не транзиентный оффлайн, а аномалия —
 * `MatcheckSyncWorker` репортит такие сбои, а не считает нормой. Воркер в любом
 * случае вернёт `Result.retry()`, поэтому цикл повторится с backoff.
 */
class SyncCycleTimeoutException(timeoutMs: Long) :
    Exception("sync cycle не уложился в ${timeoutMs}мс и был прерван")
