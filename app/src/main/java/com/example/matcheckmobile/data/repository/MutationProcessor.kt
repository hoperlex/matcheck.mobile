package com.example.matcheckmobile.data.repository

import android.util.Log
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toEntity
import com.example.matcheckmobile.data.remote.api.DeliveriesApi
import com.example.matcheckmobile.data.remote.api.ShipmentsApi
import com.example.matcheckmobile.data.remote.api.dto.DeliveryConflictResponse
import com.example.matcheckmobile.data.remote.api.dto.DeliveryDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentConflictResponse
import com.example.matcheckmobile.data.remote.api.dto.ShipmentDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import com.example.matcheckmobile.domain.StatusPolicy
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Push-цикл локальных мутаций на сервер. По образцу
 * apps/web/src/services/sync.ts: pushPendingMutations.
 *
 * Разбор ошибок делает [classifyMutationFailure]; обработка по семантике:
 * - 200 → пишем серверный snapshot в Room, удаляем mutation
 * - OCC conflict → пишем server-snapshot в delivery.serverSnapshotJson,
 *   conflictPending=true. Ждём UI-разрешения через ConflictRepository
 * - pending_deletion / already_pending / not_pending → перечитываем сущность
 *   через GET /:id (приходит актуальный pendingDeletionAt), drop mutation
 * - must_mark_first / cannot_mark_status → drop (клиентская логика разошлась
 *   с сервером; inspector_kpp такое инициировать не должен)
 * - 401 / 5xx / IOException → backoff: attempts++, оставляем в очереди
 *   (401 — протухший токен, сервер payload не читал; см. [httpDisposition])
 * - другие 4xx → drop (state локально сломан, retry бесполезен)
 *
 * Порядок: одна мутация блокирует следующие на той же entityId (FIFO).
 * Между разными сущностями порядка нет.
 */
class MutationProcessor(
    private val mutationDao: MutationDao,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val deliveriesApi: DeliveriesApi,
    private val shipmentsApi: ShipmentsApi,
    /** См. SyncRepository.remoteWriter — тот же инвариант для ответов сервера. */
    private val remoteWriter: PendingAwareRemoteWriter,
    /**
     * Обработка 403 `foreign_site`: снимки уходят в терминальный карантин,
     * очередь записи удаляется, сама запись — только если спасать нечего.
     */
    private val quarantine: ForeignSiteQuarantine,
    /**
     * Телеметрия факта отмены конфликтной мутации при terminal server-win
     * (только метаданные, без содержимого). Инъектируется — в JVM-тестах no-op.
     */
    private val telemetryDiscard: (List<MutationEntity>) -> Unit = DiscardTelemetry.sentry,
) {

    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun processAll(): PushResult {
        var pushed = 0
        var conflicts = 0
        var dropped = 0
        var transientErrors = 0

        // Наследие 1.0.29 (Б4): та версия не знала кода `foreign_site` и
        // складывала такие отказы как conflictPending с сырым телом в
        // lastError. Из-за предзаполнения blockedEntities из listConflicts()
        // они блокировали сущность навсегда и до карантина бы не дошли.
        // Разбираем их до основного цикла; после первого прохода запрос
        // ничего не находит — sweep идемпотентен.
        dropped += sweepLegacyForeignSiteFailures()

        // Наследие сборок до 1.0.38: 401 (протухший токен) классифицировался как
        // неисправимая ошибка и замораживал операцию навсегда. Возвращаем такие
        // мутации в очередь ДО основного цикла, чтобы они уехали этим же проходом
        // и в правильном порядке (listPending сортирует по createdAt).
        //
        // Обёрнут целиком: processAll вызывается из syncOnce БЕЗ runCatching
        // (SyncRepository), поэтому исключение отсюда оборвало бы весь цикл —
        // вместе с pull и загрузкой фото. Разбор наследия такой цены не стоит:
        // упавший sweep должен молча уступить место обычной синхронизации.
        dropped += runCatching { sweep401Failures() }.getOrDefault(0)

        val pending = mutationDao.listPending(System.currentTimeMillis())
        // FIFO в рамках одной сущности — если предыдущая мутация осталась
        // (5xx backoff / conflictPending), последующие на той же entityId
        // не трогаем до её резолюции. conflictPending-мутации в listPending НЕ
        // попадают, поэтому предзаполняем blockedEntities их сущностями — иначе
        // поздняя неконфликтная мутация обгонит замороженную и осиротит её.
        val blockedEntities = mutationDao.listConflicts()
            .map { it.entityType + ":" + it.entityId }
            .toMutableSet()

        for (m in pending) {
            val key = m.entityType + ":" + m.entityId
            if (key in blockedEntities) continue
            val outcome = runCatching { dispatch(m) }
                .fold(onSuccess = { it }, onFailure = { error -> classifyFailure(m, error) })

            when (outcome) {
                is Outcome.Ok -> {
                    mutationDao.deleteById(m.id)
                    pushed++
                }
                is Outcome.TerminalServerWin -> {
                    // Сервер уже терминальный (confirmed_mol): snapshot применён
                    // чисто в tryApplyOccSnapshot. Удаляем ТОЛЬКО текущую
                    // конфликтную мутацию; поздние операции сущности сохраняем
                    // (blockedEntities не даёт им выполниться в этом снимке
                    // pending, но не удаляет). Атомарность здесь не транзакционная,
                    // но безопасна за счёт повторяемости: при падении до deleteById
                    // мутация переиграется и снова получит терминальный 409.
                    mutationDao.deleteById(m.id)
                    blockedEntities += key
                    runCatching { telemetryDiscard(listOf(m)) } // best-effort, не влияет на sync
                    pushed++
                }
                is Outcome.Conflict -> {
                    mutationDao.upsert(
                        m.copy(
                            conflictPending = true,
                            attempts = m.attempts + 1,
                            lastError = outcome.tag,
                        ),
                    )
                    blockedEntities += key
                    conflicts++
                }
                is Outcome.PurgeForeign -> {
                    // 403 foreign_site: запись принадлежит другому объекту —
                    // это остаток прошлого аккаунта на планшете. Держать её в
                    // очереди бессмысленно (сервер будет отказывать всегда), а
                    // Drop оставил бы conflictPending и заблокировал сущность.
                    // Очередь удаляем, но НЕ данные: неотправленные снимки
                    // уходят в терминальный карантин и ждут решения человека.
                    val result = runCatching { purgeForeignEntity(m) }
                    blockedEntities += key
                    Sentry.withScope { scope ->
                        scope.setTag("entityType", m.entityType)
                        scope.setTag("operation", m.operation)
                        scope.setLevel(SentryLevel.WARNING)
                        val outcome = result.getOrNull()
                        Sentry.captureMessage(
                            when (outcome) {
                                is ForeignSiteQuarantine.Outcome.Quarantined ->
                                    "mutation purged: foreign_site (quarantined ${outcome.count} photos)"
                                ForeignSiteQuarantine.Outcome.Deleted -> "mutation purged: foreign_site (no photos)"
                                null -> "mutation purge failed: foreign_site"
                            },
                        )
                    }
                    dropped++
                }
                is Outcome.Drop -> {
                    // Drop = «мутация отброшена» (главный симптом «инспектор отправил,
                    // портал молчит»). Репортим в Sentry: только теги + КАТЕГОРИЯ причины
                    // (до ':') — без сырого тела ответа сервера, чтобы не утащить данные.
                    Sentry.withScope { scope ->
                        scope.setTag("entityType", m.entityType)
                        scope.setTag("operation", m.operation)
                        scope.setLevel(SentryLevel.WARNING)
                        Sentry.captureMessage(
                            "mutation dropped: " + outcome.reason.substringBefore(':').trim().take(80),
                        )
                    }
                    // Раньше Drop тихо удалял мутацию — диагностика терялась.
                    // Теперь сохраняем запись с lastError и conflictPending=true,
                    // чтобы видеть причину в Очереди. Очистить можно кнопкой.
                    mutationDao.upsert(
                        m.copy(
                            conflictPending = true,
                            attempts = m.attempts + 1,
                            lastError = outcome.reason,
                        ),
                    )
                    blockedEntities += key
                    dropped++
                }
                is Outcome.Backoff -> {
                    // Transient-ошибка (5xx / сеть / таймаут): НЕ удаляем мутацию,
                    // сколько бы попыток ни прошло. Операция инспектора не должна
                    // исчезнуть локально из-за временной недоступности сервера —
                    // раньше аварийный deleteById после MAX_ATTEMPTS и был
                    // механизмом «на планшете есть, на портале нет». Теперь мутация
                    // остаётся в очереди и повторяется по backoff (capped ~5 мин:
                    // backoffDelayMs) на каждом sync / SSE-событии / старте
                    // приложения, пока сервер не примет её.
                    val attempts = m.attempts + 1
                    val nextAt = System.currentTimeMillis() + backoffDelayMs(attempts)
                    mutationDao.upsert(
                        m.copy(
                            attempts = attempts,
                            nextAttemptAt = nextAt,
                            lastError = outcome.error,
                        ),
                    )
                    blockedEntities += key
                    transientErrors++
                }
            }
        }

        return PushResult(pushed = pushed, conflicts = conflicts, dropped = dropped, retried = transientErrors)
    }

    /**
     * Разбирает мутации, чей `lastError` содержит `foreign_site`, — их мог
     * записать клиент 1.0.29 в окне между деплоем сервера и обновлением
     * планшета. Обрабатываются тем же путём, что и свежий 403: снимки в
     * карантин, очередь записи снимается.
     *
     * @return сколько мутаций разобрано.
     */
    private suspend fun sweepLegacyForeignSiteFailures(): Int {
        val legacy = runCatching { mutationDao.listForeignSiteFailures() }.getOrDefault(emptyList())
        if (legacy.isEmpty()) return 0
        // Одна запись может иметь несколько мутаций — purge снимает их разом,
        // поэтому идём по уникальным сущностям.
        val byEntity = legacy.distinctBy { it.entityType + ":" + it.entityId }
        for (m in byEntity) {
            runCatching { purgeForeignEntity(m) }
        }
        Sentry.withScope { scope ->
            scope.setLevel(SentryLevel.WARNING)
            scope.setTag("entities", byEntity.size.toString())
            Sentry.captureMessage("legacy foreign_site mutations swept")
        }
        return byEntity.size
    }

    /** Что sweep делает с конкретной замороженной 401-мутацией. */
    private enum class Sweep401Action { UNFREEZE, DISCARD, KEEP_FROZEN }

    /**
     * Возвращает в очередь мутации, отброшенные из-за 401 сборками до 1.0.38.
     *
     * Выборка ([MutationDao.listFrozen401]) строго по `conflictPending = 1`:
     * после правки [httpDisposition] свежий 401 уходит в `Backoff` и остаётся
     * рабочей мутацией с таким же `lastError`. Трогать её нельзя — иначе sweep
     * сбрасывал бы `attempts` и ломал экспоненту. По той же причине он
     * одноразовый по построению: разморозив мутацию, он выводит её из выборки
     * навсегда, а вернуть `conflictPending` с тем же `lastError` больше некому.
     *
     * Разморозка — условным UPDATE, а не записью объекта: пока идём по
     * прочитанному списку, пользователь мог выполнить новое действие, и
     * `DeliveryRepository.upsert` снял прежнюю мутацию через `deleteFor`.
     *
     * @return сколько мутаций удалено (разморожённые считает основной цикл).
     */
    private suspend fun sweep401Failures(): Int {
        val frozen = runCatching { mutationDao.listFrozen401() }.getOrDefault(emptyList())
        if (frozen.isEmpty()) return 0

        var unfrozen = 0
        var discarded = 0
        var kept = 0
        for (m in frozen) {
            when (sweep401Action(m)) {
                Sweep401Action.UNFREEZE -> {
                    // 0 строк = мутацию уже заменило действие пользователя. Это
                    // штатный исход гонки, а не ошибка: свежая операция актуальнее.
                    val touched = runCatching { mutationDao.unfreezeIfFrozen(m.id) }.getOrDefault(0)
                    if (touched > 0) {
                        unfrozen++
                        Log.w(SWEEP_TAG, "unfrozen: ${m.entityType} ${m.operation} ${m.entityId}")
                    }
                }
                Sweep401Action.DISCARD -> {
                    runCatching { mutationDao.deleteById(m.id) }
                    discarded++
                    Log.w(SWEEP_TAG, "discarded orphan: ${m.entityType} ${m.operation} ${m.entityId}")
                }
                Sweep401Action.KEEP_FROZEN -> kept++
            }
        }

        // Отчитываемся только когда что-то изменилось. Иначе мутация, оставленная
        // замороженной (незнакомая операция), попадала бы в выборку на каждом
        // цикле синка и шумела в логе вечно.
        if (unfrozen > 0 || discarded > 0) {
            Log.w(SWEEP_TAG, "swept: unfrozen=$unfrozen discarded=$discarded kept=$kept")
            Sentry.withScope { scope ->
                scope.setLevel(SentryLevel.WARNING)
                scope.setTag("unfrozen", unfrozen.toString())
                scope.setTag("discarded", discarded.toString())
                scope.setTag("kept", kept.toString())
                Sentry.captureMessage("legacy http 401 mutations swept")
            }
        }
        return discarded
    }

    /**
     * Политика зависит от операции, а не только от наличия локальной записи.
     *
     * `delete` размораживается всегда: hard-delete СНАЧАЛА сносит локальную
     * строку и только потом ставит мутацию в очередь (`DeliveryRepository.delete`,
     * симметрично `ShipmentRepository`). Замороженный `delete` поэтому всегда
     * выглядит осиротевшим, и выброс такой мутации оставил бы запись на сервере
     * навсегда — ровно та потеря, от которой лечимся.
     *
     * Для остальных операций отсутствие родителя означает, что сервер прислал
     * tombstone и `SyncRepository` снёс локальную строку, не тронув мутацию.
     * Разморозка воскресила бы удалённую запись, поэтому мутацию убираем.
     *
     * Незнакомые тип/операция остаются замороженными: молча удалять то, чего
     * не понимаем, нельзя.
     */
    private suspend fun sweep401Action(m: MutationEntity): Sweep401Action {
        if (m.entityType != ForeignSiteQuarantine.ENTITY_DELIVERY &&
            m.entityType != ForeignSiteQuarantine.ENTITY_SHIPMENT
        ) {
            return Sweep401Action.KEEP_FROZEN
        }
        return when (m.operation) {
            "delete" -> Sweep401Action.UNFREEZE
            "upsert", "mark_deletion", "unmark_deletion" ->
                if (hasLocalParent(m)) Sweep401Action.UNFREEZE else Sweep401Action.DISCARD
            else -> Sweep401Action.KEEP_FROZEN
        }
    }

    private suspend fun hasLocalParent(m: MutationEntity): Boolean = when (m.entityType) {
        ForeignSiteQuarantine.ENTITY_DELIVERY -> deliveryDao.findById(m.entityId) != null
        ForeignSiteQuarantine.ENTITY_SHIPMENT -> shipmentDao.findById(m.entityId) != null
        else -> false
    }

    private suspend fun dispatch(m: MutationEntity): Outcome {
        return when (m.entityType) {
            "delivery" -> dispatchDelivery(m)
            "shipment" -> dispatchShipment(m)
            else -> Outcome.Drop("unknown entityType ${m.entityType}")
        }
    }

    private suspend fun dispatchDelivery(m: MutationEntity): Outcome {
        when (m.operation) {
            "upsert" -> {
                val payload = m.payloadJson?.let {
                    json.decodeFromString(DeliveryUpsertRequest.serializer(), it)
                } ?: return Outcome.Drop("payloadJson отсутствует")
                val server = deliveriesApi.upsert(payload)
                saveDeliveryFromServer(server)
                return Outcome.Ok
            }
            "delete" -> {
                deliveriesApi.delete(m.entityId)
                deliveryDao.deleteByIds(listOf(m.entityId))
                return Outcome.Ok
            }
            "mark_deletion" -> {
                val reason = m.payloadJson?.let {
                    json.decodeFromString(MarkDeletionRequest.serializer(), it)
                } ?: MarkDeletionRequest()
                val server = deliveriesApi.markDeletion(m.entityId, reason)
                saveDeliveryFromServer(server)
                return Outcome.Ok
            }
            "unmark_deletion" -> {
                val server = deliveriesApi.unmarkDeletion(m.entityId)
                saveDeliveryFromServer(server)
                return Outcome.Ok
            }
            else -> return Outcome.Drop("unknown operation ${m.operation}")
        }
    }

    private suspend fun dispatchShipment(m: MutationEntity): Outcome {
        when (m.operation) {
            "upsert" -> {
                val payload = m.payloadJson?.let {
                    json.decodeFromString(ShipmentUpsertRequest.serializer(), it)
                } ?: return Outcome.Drop("payloadJson отсутствует")
                val server = shipmentsApi.upsert(payload)
                saveShipmentFromServer(server)
                return Outcome.Ok
            }
            "delete" -> {
                shipmentsApi.delete(m.entityId)
                shipmentDao.deleteByIds(listOf(m.entityId))
                return Outcome.Ok
            }
            "mark_deletion" -> {
                val reason = m.payloadJson?.let {
                    json.decodeFromString(MarkDeletionRequest.serializer(), it)
                } ?: MarkDeletionRequest()
                val server = shipmentsApi.markDeletion(m.entityId, reason)
                saveShipmentFromServer(server)
                return Outcome.Ok
            }
            "unmark_deletion" -> {
                val server = shipmentsApi.unmarkDeletion(m.entityId)
                saveShipmentFromServer(server)
                return Outcome.Ok
            }
            else -> return Outcome.Drop("unknown operation ${m.operation}")
        }
    }

    /**
     * Убирает из очереди запись чужого объекта, не теряя её содержимое.
     * Вся работа — в [ForeignSiteQuarantine]: неотправленные снимки получают
     * терминальный статус и остаются на планшете, мутации удаляются, сама
     * запись исчезает только когда спасать нечего.
     */
    private suspend fun purgeForeignEntity(m: MutationEntity): ForeignSiteQuarantine.Outcome? =
        when (m.entityType) {
            ForeignSiteQuarantine.ENTITY_DELIVERY -> quarantine.quarantineDelivery(m.entityId)
            ForeignSiteQuarantine.ENTITY_SHIPMENT -> quarantine.quarantineShipment(m.entityId)
            // Неизвестный тип: спасать нечего, просто снимаем мутацию с очереди.
            else -> { mutationDao.deleteById(m.id); null }
        }

    private suspend fun saveDeliveryFromServer(dto: DeliveryDto) {
        remoteWriter.saveDelivery(
            delivery = dto.toEntity(),
            items = dto.items.map { it.toEntity(dto.id) },
            photos = dto.photos.map { it.toEntity(dto.id) },
        )
    }

    private suspend fun saveShipmentFromServer(dto: ShipmentDto) {
        remoteWriter.saveShipment(
            shipment = dto.toEntity(),
            items = dto.items.map { it.toEntity(dto.id) },
            photos = dto.photos.map { it.toEntity(dto.id) },
        )
    }

    private suspend fun classifyFailure(m: MutationEntity, error: Throwable): Outcome {
        if (error is IOException) return Outcome.Backoff(error.message ?: "io")
        if (error !is HttpException) return Outcome.Backoff(error.message ?: "unknown")

        val code = error.code()
        val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        val failure = classifyMutationFailure(code, raw)

        // Усекаем тело ответа, чтобы lastError не разрастался.
        val rawSnippet = raw?.take(300)
        return when (failure) {
            is MutationFailure.Conflict -> {
                val isIdempotentCreate = m.operation == "upsert" && (m.baseVersion ?: 0) == 0
                when (tryApplyOccSnapshot(m, raw, isIdempotentCreate)) {
                    SnapshotResult.NOT_APPLIED -> Outcome.Drop("http 409 без snapshot: $rawSnippet")
                    SnapshotResult.APPLIED_IDEMPOTENT -> Outcome.Ok
                    SnapshotResult.APPLIED_TERMINAL -> Outcome.TerminalServerWin
                    SnapshotResult.APPLIED_CONFLICT -> Outcome.Conflict(failure.tag)
                }
            }
            MutationFailure.PendingDeletion,
            MutationFailure.AlreadyPending,
            MutationFailure.NotPending -> {
                tryRefreshFromServer(m)
                Outcome.Drop("http $code · ${failure}")
            }
            MutationFailure.MustMarkFirst,
            MutationFailure.CannotMarkStatus -> {
                Outcome.Drop("http $code · ${failure}")
            }
            MutationFailure.ForeignSite -> Outcome.PurgeForeign
            is MutationFailure.Other -> when (httpDisposition(code)) {
                HttpDisposition.RETRY -> Outcome.Backoff("http $code · $rawSnippet")
                HttpDisposition.DROP -> Outcome.Drop("http $code · $rawSnippet")
            }
        }
    }

    private enum class SnapshotResult { NOT_APPLIED, APPLIED_IDEMPOTENT, APPLIED_TERMINAL, APPLIED_CONFLICT }

    /**
     * Применяет серверный snapshot из тела 409-ответа в Room и сообщает, как
     * трактовать конфликт. Флаг `conflictPending` ставится только когда правку
     * НЕ отпускаем (`markConflict`): не для idempotent-create и не для терминала.
     *
     * Terminal server-win работает для ОБЕИХ сущностей. Комментарий про
     * «только delivery» устарел вместе с областью той правки: ветка shipment
     * ниже считает терминал так же, и расходиться им нельзя — иначе выезд
     * замирал бы на 2 Этапе там, где приёмка разрешается сама.
     */
    private suspend fun tryApplyOccSnapshot(
        m: MutationEntity,
        raw: String?,
        isIdempotentCreate: Boolean,
    ): SnapshotResult {
        if (raw.isNullOrBlank()) return SnapshotResult.NOT_APPLIED
        return when (m.entityType) {
            "delivery" -> runCatching {
                val parsed = json.decodeFromString(DeliveryConflictResponse.serializer(), raw)
                // Auto server-win — строго только для целевого D2 upsert. Конфликтные
                // mark_deletion/unmark_deletion/будущие операции остаются на ручную
                // резолюцию (терминал для них не считаем).
                val terminal = m.operation == "upsert" && StatusPolicy.isTerminal(parsed.server.status.code)
                val markConflict = !isIdempotentCreate && !terminal
                val entity = parsed.server.toEntity().copy(
                    conflictPending = markConflict,
                    serverSnapshotJson = if (markConflict) raw else null,
                    lastSyncError = if (markConflict) "conflict serverVersion=${parsed.serverVersion}" else null,
                )
                remoteWriter.saveDelivery(
                    delivery = entity,
                    items = parsed.server.items.map { it.toEntity(parsed.server.id) },
                    photos = parsed.server.photos.map { it.toEntity(parsed.server.id) },
                )
                when {
                    isIdempotentCreate -> SnapshotResult.APPLIED_IDEMPOTENT // приоритет: create без потери правки
                    terminal -> SnapshotResult.APPLIED_TERMINAL
                    else -> SnapshotResult.APPLIED_CONFLICT
                }
            }.getOrDefault(SnapshotResult.NOT_APPLIED)
            "shipment" -> runCatching {
                val parsed = json.decodeFromString(ShipmentConflictResponse.serializer(), raw)
                // Симметрично приёмкам: если сервер уже перевёл выезд в терминал
                // (confirmed_mol), доставить туда локальную правку невозможно —
                // запись финализирована и ушла в архив, спорить не о чем.
                // Раньше терминал для отгрузок не учитывался, и выезд замирал
                // на 2 Этапе до ручного разрешения.
                val terminal = m.operation == "upsert" && StatusPolicy.isTerminal(parsed.server.status.code)
                val markConflict = !isIdempotentCreate && !terminal
                val entity = parsed.server.toEntity().copy(
                    conflictPending = markConflict,
                    serverSnapshotJson = if (markConflict) raw else null,
                    lastSyncError = if (markConflict) "conflict serverVersion=${parsed.serverVersion}" else null,
                )
                remoteWriter.saveShipment(
                    shipment = entity,
                    items = parsed.server.items.map { it.toEntity(parsed.server.id) },
                    photos = parsed.server.photos.map { it.toEntity(parsed.server.id) },
                )
                when {
                    isIdempotentCreate -> SnapshotResult.APPLIED_IDEMPOTENT // приоритет: create без потери правки
                    terminal -> SnapshotResult.APPLIED_TERMINAL
                    else -> SnapshotResult.APPLIED_CONFLICT
                }
            }.getOrDefault(SnapshotResult.NOT_APPLIED)
            else -> SnapshotResult.NOT_APPLIED
        }
    }

    private suspend fun tryRefreshFromServer(m: MutationEntity) {
        runCatching {
            when (m.entityType) {
                "delivery" -> saveDeliveryFromServer(deliveriesApi.get(m.entityId))
                "shipment" -> saveShipmentFromServer(shipmentsApi.get(m.entityId))
            }
        }
    }

    /**
     * Лечит мутации, застрявшие в [conflictPending] из-за «create-on-existing»:
     * клиент шлёт baseVersion=0 на повторе после network-glitch — сервер
     * отвечает 409. Делает GET /:id → если сервер знает запись, считаем её
     * успешно созданной, сбрасываем conflictPending и удаляем мутацию.
     *
     * Если сервер вернул 404 на GET — приёмка реально не существует (могла
     * быть удалена / другой siteId / другой инспектор); такую мутацию тоже
     * удаляем — держать её в очереди бессмысленно. Прочие ошибки записываем
     * в lastError, чтобы было видно в Очереди синхронизации.
     */
    /**
     * Полная очистка очереди мутаций. Удаляет все pending и conflictPending
     * записи, снимает флаг conflictPending у локальных entity. Делает «hard
     * reset» состояния push'а — нужен, когда зомби-мутации накопились и
     * блокируют отправку свежих приёмок. Локальные delivery/shipment не
     * удаляются: при следующем sync подтянутся актуальные с сервера.
     */
    suspend fun clearAll(): Int {
        // Long.MAX_VALUE — берём ВСЕ pending независимо от backoff-паузы
        // (nextAttemptAt), т.к. это полная очистка: в backoff-мутации тоже
        // должны попасть под hard-reset.
        val all = mutationDao.listPending(Long.MAX_VALUE) + mutationDao.listConflicts()
        for (m in all) {
            mutationDao.deleteById(m.id)
            when (m.entityType) {
                "delivery" -> deliveryDao.findById(m.entityId)?.let {
                    if (it.conflictPending) {
                        deliveryDao.upsert(
                            it.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null),
                        )
                    }
                }
                "shipment" -> shipmentDao.findById(m.entityId)?.let {
                    if (it.conflictPending) {
                        shipmentDao.upsert(
                            it.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null),
                        )
                    }
                }
            }
        }
        return all.size
    }

    suspend fun resolveStaleCreateConflicts(): Int {
        val stale = mutationDao.listConflicts()
            .filter { it.operation == "upsert" && (it.baseVersion ?: 0) == 0 }
        var resolved = 0
        for (m in stale) {
            try {
                when (m.entityType) {
                    "delivery" -> saveDeliveryFromServer(deliveriesApi.get(m.entityId))
                    "shipment" -> saveShipmentFromServer(shipmentsApi.get(m.entityId))
                    else -> continue
                }
                when (m.entityType) {
                    "delivery" -> deliveryDao.findById(m.entityId)?.let {
                        deliveryDao.upsert(it.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null))
                    }
                    "shipment" -> shipmentDao.findById(m.entityId)?.let {
                        shipmentDao.upsert(it.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null))
                    }
                }
                mutationDao.deleteById(m.id)
                resolved++
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    // Приёмки на сервере нет — нет смысла держать мутацию.
                    // Также чистим локальную «остатки», чтобы UI не показывал.
                    mutationDao.deleteById(m.id)
                    when (m.entityType) {
                        "delivery" -> deliveryDao.deleteByIds(listOf(m.entityId))
                        "shipment" -> shipmentDao.deleteByIds(listOf(m.entityId))
                    }
                    resolved++
                } else {
                    mutationDao.upsert(m.copy(lastError = "resolve: http ${e.code()}"))
                }
            } catch (e: Throwable) {
                mutationDao.upsert(m.copy(lastError = "resolve: ${e.message ?: e::class.simpleName}"))
            }
        }
        return resolved
    }

    private fun backoffDelayMs(attempts: Int): Long {
        // 2^attempts seconds, capped at 5 min. Никаких jitter — Worker сам
        // запустится со своим интервалом.
        val seconds = (1L shl attempts.coerceAtMost(8)).coerceAtMost(300L)
        return seconds * 1000L
    }

    sealed interface Outcome {
        data object Ok : Outcome
        /** 409, но сервер уже терминальный (delivery confirmed_mol): server-win — удалить конфликтную мутацию. */
        data object TerminalServerWin : Outcome
        data class Conflict(val tag: String) : Outcome
        data class Drop(val reason: String) : Outcome
        /**
         * 403 foreign_site: сервер отказал, потому что запись принадлежит
         * другому объекту. Очередь этой записи снимаем — она не должна ни
         * отправляться, ни блокировать FIFO. Содержимое не теряем: снимки
         * уходят в карантин (см. [ForeignSiteQuarantine]).
         */
        data object PurgeForeign : Outcome
        data class Backoff(val error: String) : Outcome
    }

    data class PushResult(
        val pushed: Int,
        val conflicts: Int,
        val dropped: Int,
        val retried: Int,
    )

    private companion object {
        /**
         * Логи разбора наследия 401. Sentry в проде выключен (пустой DSN), поэтому
         * logcat — единственный след на устройстве; на канареечном планшете
         * читается через `adb logcat -s Sweep401`.
         */
        const val SWEEP_TAG = "Sweep401"
    }
}
