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
 * Поведение по статусам ответа:
 * - 200 → пишем серверный snapshot в Room, удаляем mutation
 * - 409 conflict (OCC) → пишем server-snapshot в delivery.serverSnapshotJson,
 *   ставим conflictPending=true на mutation. Ждём UI-разрешения через
 *   ConflictRepository
 * - 409 soft-delete-семантика (must_mark_first / already_pending / not_pending
 *   / pending_deletion) → перечитываем сущность с сервера, drop mutation
 *   (она "морально устарела")
 * - 400 cannot_mark_status → drop, логируем
 * - 5xx / IOException → backoff: attempts++, оставляем в очереди
 * - другие 4xx → drop (бажный локальный state, retry бесполезен)
 *
 * При обработке push-mutations порядок гарантируется: одна мутация
 * блокирует следующие на той же сущности (FIFO). Между разными сущностями
 * порядка нет.
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
                            lastError = "conflict",
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

        if (code == 409) {
            // OCC conflict — есть server snapshot. Парсим как Delivery/ShipmentConflictResponse.
            val occHandled = tryHandleOccConflict(m, raw)
            if (occHandled) return Outcome.Conflict

            // Soft-delete семантика — перечитываем сущность, drop mutation.
            tryRefreshAfterSoftConflict(m)
            return Outcome.Drop
        }
        if (code in 500..599) return Outcome.Backoff("http $code")
        return Outcome.Drop // 4xx (кроме 409) — состояние локально сломано
    }

    private suspend fun tryHandleOccConflict(m: MutationEntity, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return when (m.entityType) {
            "delivery" -> runCatching {
                val parsed = json.decodeFromString(DeliveryConflictResponse.serializer(), raw)
                if (parsed.error != "conflict") return@runCatching false
                val entity = parsed.server.toEntity().copy(
                    conflictPending = true,
                    serverSnapshotJson = raw,
                    lastSyncError = "conflict serverVersion=${parsed.serverVersion}",
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
                if (parsed.error != "conflict") return@runCatching false
                val entity = parsed.server.toEntity().copy(
                    conflictPending = true,
                    serverSnapshotJson = raw,
                    lastSyncError = "conflict serverVersion=${parsed.serverVersion}",
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

    private suspend fun tryRefreshAfterSoftConflict(m: MutationEntity) {
        runCatching {
            when (m.entityType) {
                "delivery" -> saveDeliveryFromServer(deliveriesApi.get(m.entityId))
                "shipment" -> saveShipmentFromServer(shipmentsApi.get(m.entityId))
            }
        }
    }

    private fun backoffDelayMs(attempts: Int): Long {
        // 2^attempts seconds, capped at 5 min. Никаких jitter — Worker сам
        // запустится со своим интервалом.
        val seconds = (1L shl attempts.coerceAtMost(8)).coerceAtMost(300L)
        return seconds * 1000L
    }

    sealed interface Outcome {
        data object Ok : Outcome
        data object Conflict : Outcome
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
