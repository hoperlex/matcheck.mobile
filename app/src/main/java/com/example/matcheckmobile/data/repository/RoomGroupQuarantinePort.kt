package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.DeliveryLocalMetaDao
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.QuarantinedOperationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import kotlinx.coroutines.flow.Flow

/** [GroupQuarantinePort] поверх Room. */
class RoomGroupQuarantinePort(
    private val quarantineDao: QuarantinedOperationDao,
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val metaDao: DeliveryLocalMetaDao,
    private val mutationDao: MutationDao,
    private val tx: TransactionRunner,
) : GroupQuarantinePort {

    override fun observeAll(): Flow<List<QuarantinedOperationEntity>> = quarantineDao.observeAll()

    override suspend fun deliveryAggregate(deliveryId: String): DeliveryAggregate? = tx.run {
        val delivery = deliveryDao.findById(deliveryId) ?: return@run null
        DeliveryAggregate(
            delivery = delivery,
            items = deliveryDao.findItemsByDelivery(deliveryId),
            photos = deliveryDao.findPhotosByDelivery(deliveryId),
            vehicleTypeCode = metaDao.getByDeliveryId(deliveryId)?.vehicleTypeCode,
        )
    }

    override suspend fun commit(row: QuarantinedOperationEntity) = tx.run {
        quarantineDao.insert(row)
        mutationDao.deleteFor(row.entityType, row.entityId)
        // Каскадом уходят позиции, строки фото и delivery_local_meta: снимок и
        // копии файлов уже у записи карантина. Локальная приёмка не должна
        // остаться — она держала бы документы машины «привязанными» и прятала
        // её карточку из списка 1 Этапа.
        deliveryDao.deleteByIds(listOf(row.entityId))
    }

    override suspend fun find(id: String): QuarantinedOperationEntity? = quarantineDao.findById(id)

    override suspend fun markReissued(id: String, at: Long) = quarantineDao.markReissued(id, at)

    override suspend fun delete(id: String) = quarantineDao.deleteById(id)

    override suspend fun sourceStillUsed(path: String): Boolean =
        deliveryDao.countAwaitingPrepareForSource(path) + shipmentDao.countAwaitingPrepareForSource(path) > 0
}
