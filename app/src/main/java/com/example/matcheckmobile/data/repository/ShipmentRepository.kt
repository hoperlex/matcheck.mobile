package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.ShipmentLocalMetaDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentItemEntity
import com.example.matcheckmobile.data.local.entity.ShipmentLocalMetaEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertItem
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID

/** Зеркально [DeliveryRepository], с обязательным [UpsertInput.kind]. */
class ShipmentRepository(
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
    private val localMetaDao: ShipmentLocalMetaDao,
) {

    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun observeActive(): Flow<List<RemoteShipmentEntity>> = shipmentDao.observeActive()
    fun observeTrash(): Flow<List<RemoteShipmentEntity>> = shipmentDao.observeTrash()
    fun observeByStatuses(statuses: List<String>): Flow<List<RemoteShipmentEntity>> =
        shipmentDao.observeByStatuses(statuses)

    suspend fun findById(id: String): RemoteShipmentEntity? = shipmentDao.findById(id)

    suspend fun findItemsByShipment(shipmentId: String): List<RemoteShipmentItemEntity> =
        shipmentDao.findItemsByShipment(shipmentId)

    // Локальная мета — vehicleTypeCode (Газель/Фура/…). В DTO отгрузки на
    // сервере такого поля нет, иначе при /sync терялось бы между этапами.
    // Зеркало DeliveryRepository.observeVehicleType / get / set.
    fun observeVehicleType(shipmentId: String): Flow<String?> =
        localMetaDao.observeVehicleTypeCode(shipmentId)

    suspend fun getVehicleType(shipmentId: String): String? =
        localMetaDao.getByShipmentId(shipmentId)?.vehicleTypeCode

    suspend fun setVehicleType(shipmentId: String, code: String?) {
        localMetaDao.upsert(ShipmentLocalMetaEntity(shipmentId = shipmentId, vehicleTypeCode = code))
    }

    suspend fun upsert(input: UpsertInput): String {
        val id = input.id ?: UUID.randomUUID().toString()
        val now = currentIsoTimestamp()
        val baseVersion = shipmentDao.findById(id)?.version ?: 0

        val items = input.items.mapIndexed { idx, it ->
            RemoteShipmentItemEntity(
                id = it.id ?: UUID.randomUUID().toString(),
                shipmentId = id,
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

        val entity = RemoteShipmentEntity(
            id = id,
            statusCode = input.statusCode,
            statusLabel = input.statusLabel ?: input.statusCode,
            statusColor = input.statusColor,
            kind = input.kind,
            purpose = input.purpose,
            inTransit = input.inTransit,
            isAssets = input.isAssets,
            siteId = input.siteId,
            receiverCounterpartyId = input.receiverCounterpartyId,
            receiverMolId = input.receiverMolId,
            destSiteId = input.destSiteId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            shippedAt = input.shippedAt,
            inspectorId = input.inspectorId,
            comment = input.comment,
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
        shipmentDao.saveAggregate(shipment = entity, items = items, photos = emptyList())

        val request = ShipmentUpsertRequest(
            id = id,
            statusCode = input.statusCode,
            kind = input.kind,
            siteId = input.siteId,
            receiverCounterpartyId = input.receiverCounterpartyId,
            receiverMolId = input.receiverMolId,
            destSiteId = input.destSiteId,
            vehiclePlate = input.vehiclePlate,
            driverName = input.driverName,
            shippedAt = input.shippedAt,
            comment = input.comment,
            purpose = input.purpose,
            inTransit = input.inTransit,
            isAssets = input.isAssets,
            sourceDocumentIds = input.sourceDocumentIds,
            items = items.map {
                ShipmentUpsertItem(
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

        mutationDao.deleteFor("shipment", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "shipment",
                operation = "upsert",
                entityId = id,
                baseVersion = baseVersion,
                payloadJson = json.encodeToString(ShipmentUpsertRequest.serializer(), request),
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
                conflictPending = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun markForDeletion(id: String, reason: String?): Result<Unit> = runCatching {
        val current = shipmentDao.findById(id) ?: return@runCatching
        val now = currentIsoTimestamp()
        shipmentDao.upsert(
            current.copy(
                pendingDeletionAt = now,
                pendingDeletionReason = reason,
            ),
        )
        mutationDao.deleteFor("shipment", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "shipment",
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
        val current = shipmentDao.findById(id) ?: return@runCatching
        shipmentDao.upsert(
            current.copy(
                pendingDeletionAt = null,
                pendingDeletionByUserId = null,
                pendingDeletionByUserEmail = null,
                pendingDeletionReason = null,
            ),
        )
        mutationDao.deleteFor("shipment", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "shipment",
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

    suspend fun delete(id: String): Result<Unit> = runCatching {
        val current = shipmentDao.findById(id) ?: return@runCatching
        if (current.statusCode !in DELETABLE_STATUSES) {
            error("delete blocked for status=${current.statusCode}, нужно mark-deletion")
        }
        shipmentDao.deleteByIds(listOf(id))
        mutationDao.deleteFor("shipment", id)
        mutationDao.upsert(
            MutationEntity(
                id = UUID.randomUUID().toString(),
                entityType = "shipment",
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

    private fun currentIsoTimestamp(): String =
        java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    data class UpsertInput(
        val id: String? = null,
        val statusCode: String,
        val statusLabel: String? = null,
        val statusColor: String? = null,
        val kind: String, // 'contractor' | 'return' | 'transfer' | 'writeoff'
        val siteId: String,
        val receiverCounterpartyId: String? = null,
        /** XOR с receiverCounterpartyId на стороне сервера. */
        val receiverMolId: String? = null,
        val destSiteId: String? = null,
        val vehiclePlate: String? = null,
        val driverName: String? = null,
        val shippedAt: String? = null,
        val inspectorId: String? = null,
        val comment: String? = null,
        /** «Тип отгрузки» — см. ShipmentDto.purpose. */
        val purpose: String? = null,
        /** Транзит — см. ShipmentDto.inTransit. */
        val inTransit: Boolean = false,
        /** ОС — см. ShipmentDto.isAssets. */
        val isAssets: Boolean = false,
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
