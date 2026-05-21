package com.example.matcheckmobile.data.repository

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
 * - 5xx / IOException → backoff: attempts++, оставляем в очереди
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
) {

    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun processAll(): PushResult {
        var pushed = 0
        var conflicts = 0
        var dropped = 0
        var transientErrors = 0

        val pending = mutationDao.listPending()
        // FIFO в рамках одной сущности — если предыдущая мутация осталась
        // (5xx backoff / conflictPending), последующие на той же entityId
        // не трогаем до её резолюции.
        val blockedEntities = mutableSetOf<String>()

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
                is Outcome.Drop -> {
                    mutationDao.deleteById(m.id)
                    dropped++
                }
                is Outcome.Backoff -> {
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
                    if (attempts >= MAX_ATTEMPTS) {
                        // Аварийный drop — лучше потерять одну мутацию, чем
                        // вечно крутить очередь.
                        mutationDao.deleteById(m.id)
                    }
                }
            }
        }

        return PushResult(pushed = pushed, conflicts = conflicts, dropped = dropped, retried = transientErrors)
    }

    private suspend fun dispatch(m: MutationEntity): Outcome {
        return when (m.entityType) {
            "delivery" -> dispatchDelivery(m)
            "shipment" -> dispatchShipment(m)
            else -> Outcome.Drop // неизвестный тип — drop
        }
    }

    private suspend fun dispatchDelivery(m: MutationEntity): Outcome {
        when (m.operation) {
            "upsert" -> {
                val payload = m.payloadJson?.let {
                    json.decodeFromString(DeliveryUpsertRequest.serializer(), it)
                } ?: return Outcome.Drop
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
            else -> return Outcome.Drop
        }
    }

    private suspend fun dispatchShipment(m: MutationEntity): Outcome {
        when (m.operation) {
            "upsert" -> {
                val payload = m.payloadJson?.let {
                    json.decodeFromString(ShipmentUpsertRequest.serializer(), it)
                } ?: return Outcome.Drop
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
            else -> return Outcome.Drop
        }
    }

    private suspend fun saveDeliveryFromServer(dto: DeliveryDto) {
        deliveryDao.saveAggregate(
            delivery = dto.toEntity(),
            items = dto.items.map { it.toEntity(dto.id) },
            photos = dto.photos.map { it.toEntity(dto.id) },
        )
    }

    private suspend fun saveShipmentFromServer(dto: ShipmentDto) {
        shipmentDao.saveAggregate(
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

        return when (failure) {
            is MutationFailure.Conflict -> {
                // Идемпотентность create: baseVersion=0 + 409 значит, что
                // первая попытка фактически создала запись на сервере, но
                // мобайл не получил response (TLS/connection reset). Snapshot
                // от сервера = успех, считаем мутацию выполненной и сохраняем
                // entity БЕЗ conflictPending.
                val isIdempotentCreate = m.operation == "upsert" && (m.baseVersion ?: 0) == 0
                val applied = tryApplyOccSnapshot(m, raw, markConflictPending = !isIdempotentCreate)
                when {
                    !applied -> Outcome.Drop
                    isIdempotentCreate -> Outcome.Ok
                    else -> Outcome.Conflict(failure.tag)
                }
            }
            MutationFailure.PendingDeletion,
            MutationFailure.AlreadyPending,
            MutationFailure.NotPending -> {
                // Локальная копия отстала: документ либо помечен на удаление,
                // либо уже в нужном soft-delete-состоянии. Освежаем GET /:id —
                // там придёт актуальный pendingDeletionAt/By/Reason.
                tryRefreshFromServer(m)
                Outcome.Drop
            }
            MutationFailure.MustMarkFirst,
            MutationFailure.CannotMarkStatus -> {
                // Клиентская логика разошлась с серверной. Inspector_kpp не
                // должен пытаться финальный DELETE (must_mark_first — это
                // только admin) или mark по черновику (cannot_mark_status —
                // надо было обычный DELETE). Drop с описательным lastError.
                Outcome.Drop
            }
            is MutationFailure.Other -> {
                if (code in 500..599) Outcome.Backoff("http $code")
                else Outcome.Drop
            }
        }
    }

    private suspend fun tryApplyOccSnapshot(
        m: MutationEntity,
        raw: String?,
        markConflictPending: Boolean = true,
    ): Boolean {
        if (raw.isNullOrBlank()) return false
        return when (m.entityType) {
            "delivery" -> runCatching {
                val parsed = json.decodeFromString(DeliveryConflictResponse.serializer(), raw)
                val entity = parsed.server.toEntity().copy(
                    conflictPending = markConflictPending,
                    serverSnapshotJson = if (markConflictPending) raw else null,
                    lastSyncError = if (markConflictPending) "conflict serverVersion=${parsed.serverVersion}" else null,
                )
                deliveryDao.saveAggregate(
                    delivery = entity,
                    items = parsed.server.items.map { it.toEntity(parsed.server.id) },
                    photos = parsed.server.photos.map { it.toEntity(parsed.server.id) },
                )
                true
            }.getOrDefault(false)
            "shipment" -> runCatching {
                val parsed = json.decodeFromString(ShipmentConflictResponse.serializer(), raw)
                val entity = parsed.server.toEntity().copy(
                    conflictPending = markConflictPending,
                    serverSnapshotJson = if (markConflictPending) raw else null,
                    lastSyncError = if (markConflictPending) "conflict serverVersion=${parsed.serverVersion}" else null,
                )
                shipmentDao.saveAggregate(
                    shipment = entity,
                    items = parsed.server.items.map { it.toEntity(parsed.server.id) },
                    photos = parsed.server.photos.map { it.toEntity(parsed.server.id) },
                )
                true
            }.getOrDefault(false)
            else -> false
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
        val all = mutationDao.listPending() + mutationDao.listConflicts()
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
        data class Conflict(val tag: String) : Outcome
        data object Drop : Outcome
        data class Backoff(val error: String) : Outcome
    }

    data class PushResult(
        val pushed: Int,
        val conflicts: Int,
        val dropped: Int,
        val retried: Int,
    )

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
