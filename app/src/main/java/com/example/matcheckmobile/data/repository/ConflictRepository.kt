package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * UI-обёртка над списком конфликтных мутаций. Три стратегии резолва:
 *
 * - server_win — выбрасываем локальные изменения, оставляем серверный
 *   snapshot. Mutation удаляется.
 * - local_win — пересылаем тот же payload с baseVersion = serverVersion;
 *   conflictPending снимается, очередь повторно подхватит mutation на
 *   ближайшем sync.
 * - merge — на этом этапе не реализуем сложный merge UI (delivery — это
 *   агрегат с items и photos, что в обычном UI «склеить» нельзя). Логику
 *   merge добавим в Этапе 6, когда появятся редакторы.
 */
class ConflictRepository(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
) {

    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun observeDeliveryConflicts(): Flow<List<RemoteDeliveryEntity>> = deliveryDao.observeConflicts()
    fun observeShipmentConflicts(): Flow<List<RemoteShipmentEntity>> = shipmentDao.observeConflicts()

    suspend fun resolveDelivery(id: String, strategy: ConflictStrategy): Result<Unit> = runCatching {
        val entity = deliveryDao.findById(id) ?: error("delivery $id not found")
        val muts = mutationDao.findFor("delivery", id)
        applyResolution(strategy, muts, entity.version) {
            deliveryDao.upsert(entity.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null))
        }
    }

    suspend fun resolveShipment(id: String, strategy: ConflictStrategy): Result<Unit> = runCatching {
        val entity = shipmentDao.findById(id) ?: error("shipment $id not found")
        val muts = mutationDao.findFor("shipment", id)
        applyResolution(strategy, muts, entity.version) {
            shipmentDao.upsert(entity.copy(conflictPending = false, serverSnapshotJson = null, lastSyncError = null))
        }
    }

    private suspend fun applyResolution(
        strategy: ConflictStrategy,
        muts: List<MutationEntity>,
        serverVersion: Int,
        clearEntityFlag: suspend () -> Unit,
    ) {
        when (strategy) {
            ConflictStrategy.SERVER_WIN -> {
                muts.forEach { mutationDao.deleteById(it.id) }
                clearEntityFlag()
            }
            ConflictStrategy.LOCAL_WIN -> {
                // Включаем mutation обратно с baseVersion=serverVersion, в т.ч.
                // переписываем baseVersion внутри payloadJson — иначе сервер
                // снова отдаст 409 на следующем push.
                muts.forEach { m ->
                    mutationDao.upsert(
                        m.copy(
                            conflictPending = false,
                            attempts = 0,
                            nextAttemptAt = null,
                            lastError = null,
                            baseVersion = serverVersion,
                            payloadJson = rewriteBaseVersionInPayload(m, serverVersion),
                        ),
                    )
                }
                clearEntityFlag()
            }
            ConflictStrategy.MERGE -> {
                // Заглушка — реализуем в Этапе 6 после появления редакторов.
                error("merge strategy не поддерживается на этапе 3")
            }
        }
    }

    /**
     * Переписывает baseVersion внутри сериализованного DeliveryUpsertRequest /
     * ShipmentUpsertRequest. Для не-upsert операций payload не меняется
     * (mark/unmark/delete OCC не используют).
     */
    private fun rewriteBaseVersionInPayload(m: MutationEntity, newBase: Int): String? {
        val payload = m.payloadJson ?: return null
        if (m.operation != "upsert") return payload
        return runCatching {
            when (m.entityType) {
                "delivery" -> {
                    val req = json.decodeFromString(DeliveryUpsertRequest.serializer(), payload)
                    json.encodeToString(DeliveryUpsertRequest.serializer(), req.copy(baseVersion = newBase))
                }
                "shipment" -> {
                    val req = json.decodeFromString(ShipmentUpsertRequest.serializer(), payload)
                    json.encodeToString(ShipmentUpsertRequest.serializer(), req.copy(baseVersion = newBase))
                }
                else -> payload
            }
        }.getOrDefault(payload)
    }

    enum class ConflictStrategy { SERVER_WIN, LOCAL_WIN, MERGE }
}
