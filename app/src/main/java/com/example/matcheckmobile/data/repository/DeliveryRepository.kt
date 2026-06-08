package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.DeliveryLocalMetaDao
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.entity.DeliveryLocalMetaEntity
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertItem
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Local-first API над приёмками. UI всегда работает с этим репозиторием,
 * push на сервер происходит асинхронно через [MutationProcessor].
 *
 * id сущности и items — UUID v4, генерируемые на клиенте. Сервер принимает
 * клиентский id в upsert как есть, что даёт стабильную идентичность между
 * Room и БД на сервере.
 */
class DeliveryRepository(
    private val deliveryDao: RemoteDeliveryDao,
    private val mutationDao: MutationDao,
    private val localMetaDao: DeliveryLocalMetaDao,
) {

    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun observeActive(): Flow<List<RemoteDeliveryEntity>> = deliveryDao.observeActive()
    fun observeTrash(): Flow<List<RemoteDeliveryEntity>> = deliveryDao.observeTrash()
    fun observeByStatus(status: String): Flow<List<RemoteDeliveryEntity>> =
        deliveryDao.observeByStatus(status)
    fun observeByStatuses(statuses: List<String>): Flow<List<RemoteDeliveryEntity>> =
        deliveryDao.observeByStatuses(statuses)

    suspend fun findById(id: String): RemoteDeliveryEntity? = deliveryDao.findById(id)

    suspend fun findItemsByDelivery(deliveryId: String): List<RemoteDeliveryItemEntity> =
        deliveryDao.findItemsByDelivery(deliveryId)

    /**
     * Локальный тип транспорта (Ларгус/Газель/Грузовик/Фура). Хранится только
     * на устройстве — на сервере поля нет, поэтому /sync его не затирает.
     * Поток с актуальным значением для конкретной приёмки — для подписки в UI.
     */
    fun observeVehicleType(deliveryId: String): Flow<String?> =
        localMetaDao.observeVehicleTypeCode(deliveryId)

    suspend fun getVehicleType(deliveryId: String): String? =
        localMetaDao.getByDeliveryId(deliveryId)?.vehicleTypeCode

    suspend fun setVehicleType(deliveryId: String, code: String?) {
        localMetaDao.upsert(DeliveryLocalMetaEntity(deliveryId = deliveryId, vehicleTypeCode = code))
    }

    /**
     * Создаёт черновик / обновляет существующую приёмку и ставит upsert-мутацию.
     * id — клиентский UUID (можно null — сгенерим).
     */
    suspend fun upsert(input: UpsertInput): String {
        val id = input.id ?: UUID.randomUUID().toString()
        val now = currentIsoTimestamp()
        val baseVersion = deliveryDao.findById(id)?.version ?: 0

        val items = input.items.mapIndexed { idx, it ->
            RemoteDeliveryItemEntity(
                id = it.id ?: UUID.randomUUID().toString(),
                deliveryId = id,
                materialId = it.materialId,
                nameRaw = it.nameRaw,
                qtyPlanned = it.qtyPlanned,
                qtyActual = it.qtyActual,
                unit = it.unit,
                comment = it.comment,
                lineNo = it.lineNo ?: (idx + 1),
                volumeM3 = it.volumeM3,
                massKg = it.massKg,
                price = it.price,
                vatRate = it.vatRate,
                vatSum = it.vatSum,
                volumeConfidence = it.volumeConfidence,
                groupName = it.groupName,
            )
        }

        val entity = RemoteDeliveryEntity(
            id = id,
            statusCode = input.statusCode,
            statusLabel = input.statusLabel ?: input.statusCode,
            statusColor = input.statusColor,
            siteId = input.siteId,
            supplierId = input.supplierId,
            contractorId = input.contractorId,
            recipientMolId = input.recipientMolId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            arrivedAt = input.arrivedAt,
            inspectorId = input.inspectorId,
            comment = input.comment,
            inTransit = input.inTransit,
            confirmedByMolUserId = null,
            confirmedByMolUserEmail = null,
            confirmedByMolAt = null,
            pendingDeletionAt = null,
            pendingDeletionByUserId = null,
            pendingDeletionByUserEmail = null,
            pendingDeletionReason = null,
            version = baseVersion,
            sourceDocumentIdsJson = RemoteMappers.encodeIdList(input.sourceDocumentIds),
            createdAt = now,
            updatedAt = now,
            conflictPending = false,
            serverSnapshotJson = null,
            lastSyncError = null,
        )
        deliveryDao.saveAggregate(delivery = entity, items = items, photos = emptyList())

        val request = DeliveryUpsertRequest(
            id = id,
            statusCode = input.statusCode,
            siteId = input.siteId,
            supplierId = input.supplierId,
            contractorId = input.contractorId,
            recipientMolId = input.recipientMolId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            arrivedAt = input.arrivedAt,
            comment = input.comment,
            inTransit = input.inTransit,
            sourceDocumentIds = input.sourceDocumentIds,
            items = items.map {
                DeliveryUpsertItem(
                    id = it.id,
                    materialId = it.materialId,
                    nameRaw = it.nameRaw,
                    qtyPlanned = it.qtyPlanned,
                    qtyActual = it.qtyActual,
                    unit = it.unit,
                    comment = it.comment,
                    lineNo = it.lineNo,
                    volumeM3 = it.volumeM3,
                    massKg = it.massKg,
                    price = it.price,
                    vatRate = it.vatRate,
                    vatSum = it.vatSum,
                    volumeConfidence = it.volumeConfidence,
                    groupName = it.groupName,
                )
            },
            baseVersion = baseVersion,
        )

        // Если на этой сущности уже был upsert в очереди — заменяем его свежим.
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "upsert",
                entityId = id,
                baseVersion = baseVersion,
                payloadJson = json.encodeToString(DeliveryUpsertRequest.serializer(), request),
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    /**
     * Помечает приёмку на удаление (для filled / confirmed_mol). Локально
     * ставим pendingDeletionAt оптимистично — UI сразу переходит в read-only.
     */
    suspend fun markForDeletion(id: String, reason: String?): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        val now = currentIsoTimestamp()
        deliveryDao.upsert(
            current.copy(
                pendingDeletionAt = now,
                pendingDeletionReason = reason,
            ),
        )
        mutationDao.deleteFor("delivery", id) // снимаем конкурирующие upsert/unmark
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "mark_deletion",
                entityId = id,
                baseVersion = current.version,
                payloadJson = json.encodeToString(MarkDeletionRequest.serializer(), MarkDeletionRequest(reason)),
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unmarkDeletion(id: String): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        deliveryDao.upsert(
            current.copy(
                pendingDeletionAt = null,
                pendingDeletionByUserId = null,
                pendingDeletionByUserEmail = null,
                pendingDeletionReason = null,
            ),
        )
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "unmark_deletion",
                entityId = id,
                baseVersion = current.version,
                payloadJson = null,
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Удалить приёмку. Прямой DELETE разрешён только для draft / not_filled
     * (см. soft-delete семантику в MOBILE_API.md). Для остальных — нужен
     * предварительный mark-deletion и роль admin.
     */
    suspend fun delete(id: String): Result<Unit> = runCatching {
        val current = deliveryDao.findById(id) ?: return@runCatching
        if (current.statusCode !in DELETABLE_STATUSES) {
            error("delete blocked for status=${current.statusCode}, нужно mark-deletion")
        }
        deliveryDao.deleteByIds(listOf(id))
        mutationDao.deleteFor("delivery", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "delivery",
                operation = "delete",
                entityId = id,
                baseVersion = current.version,
                payloadJson = null,
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun currentIsoTimestamp(): String {
        // ISO-8601 для совместимости с сервером — серверный updatedAt всё равно перепишет.
        return java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString()
    }

    /** Минимальный набор полей для upsert. UI собирает из своих view-models. */
    data class UpsertInput(
        val id: String? = null,
        val statusCode: String,
        val statusLabel: String? = null,
        val statusColor: String? = null,
        val siteId: String,
        val supplierId: String? = null,
        val contractorId: String? = null,
        val recipientMolId: String? = null,
        val vehiclePlate: String? = null,
        val driverName: String? = null,
        val arrivedAt: String? = null,
        val inspectorId: String? = null,
        val comment: String? = null,
        /** Транзит — см. DeliveryDto.inTransit. */
        val inTransit: Boolean = false,
        val sourceDocumentIds: List<String> = emptyList(),
        val items: List<ItemInput> = emptyList(),
    )

    data class ItemInput(
        val id: String? = null,
        val materialId: String? = null,
        val nameRaw: String,
        val qtyPlanned: String? = null,
        val qtyActual: String? = null,
        val unit: String = "шт",
        val comment: String? = null,
        val lineNo: Int? = null,
        val volumeM3: String? = null,
        val massKg: String? = null,
        val price: String? = null,
        val vatRate: String? = null,
        val vatSum: String? = null,
        val volumeConfidence: String? = null,
        val groupName: String? = null,
    )

    private companion object {
        val DELETABLE_STATUSES = setOf("draft", "not_filled")
    }
}
