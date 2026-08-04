package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import kotlinx.serialization.json.Json

/**
 * Решает, чьё время подтверждения победит при записи серверного агрегата.
 *
 *  - серверное непустое значение авторитетно всегда;
 *  - серверный `null` НЕ затирает локальное время, пока в очереди лежит
 *    upsert-мутация, которая это время как раз и везёт на сервер;
 *  - как только мутация ушла из очереди, сервер снова авторитетен целиком,
 *    включая `null` — иначе устаревшее локальное значение осталось бы навсегда.
 *
 * Чистая функция: покрывается обычным JVM-тестом.
 */
internal fun pickConfirmedAt(server: String?, local: String?, hasQueuedConfirm: Boolean): String? =
    when {
        server != null -> server
        hasQueuedConfirm -> local
        else -> null
    }

/**
 * Единственная точка записи серверного агрегата в Room.
 *
 * Зачем нужен: время подтверждения теперь рождается на планшете и какое-то
 * время живёт только локально — пока мутация не доедет до сервера. Любая
 * запись серверного снимка в этом окне обнулила бы его, и «выезд» снова
 * исчез бы из архива. Серверный снимок приходит не только из обычного pull:
 * ещё reconcile/detail, ответ на upsert и OCC-снимок конфликта — защита в
 * одном из этих мест была бы дырявой.
 *
 * Чтение локальной записи, проверка очереди и сохранение идут **в одной
 * транзакции**: между проверкой и записью очередь успела бы измениться, и
 * решение принималось бы по устаревшему состоянию.
 *
 * Подменяет прямые вызовы `deliveryDao.saveAggregate` / `shipmentDao.saveAggregate`
 * и по сигнатуре с ними совпадает.
 */
class PendingAwareRemoteWriter(
    private val deliveryDao: RemoteDeliveryDao,
    private val shipmentDao: RemoteShipmentDao,
    private val mutationDao: MutationDao,
    private val tx: TransactionRunner,
) {

    private val json: Json = Json { ignoreUnknownKeys = true }

    suspend fun saveDelivery(
        delivery: RemoteDeliveryEntity,
        items: List<RemoteDeliveryItemEntity>,
        photos: List<RemoteDeliveryPhotoEntity>,
    ) = tx.run {
        val local = deliveryDao.findById(delivery.id)
        val effective = pickConfirmedAt(
            server = delivery.confirmedByMolAt,
            local = local?.confirmedByMolAt,
            hasQueuedConfirm = hasQueuedConfirm(ENTITY_DELIVERY, delivery.id) { payload ->
                json.decodeFromString(DeliveryUpsertRequest.serializer(), payload).confirmedByMolAt
            },
        )
        deliveryDao.saveAggregate(
            delivery = if (effective == delivery.confirmedByMolAt) {
                delivery
            } else {
                delivery.copy(confirmedByMolAt = effective)
            },
            items = items,
            photos = photos,
        )
    }

    suspend fun saveShipment(
        shipment: RemoteShipmentEntity,
        items: List<RemoteShipmentItemEntity>,
        photos: List<RemoteShipmentPhotoEntity>,
    ) = tx.run {
        val local = shipmentDao.findById(shipment.id)
        val effective = pickConfirmedAt(
            server = shipment.confirmedByMolAt,
            local = local?.confirmedByMolAt,
            hasQueuedConfirm = hasQueuedConfirm(ENTITY_SHIPMENT, shipment.id) { payload ->
                json.decodeFromString(ShipmentUpsertRequest.serializer(), payload).confirmedByMolAt
            },
        )
        shipmentDao.saveAggregate(
            shipment = if (effective == shipment.confirmedByMolAt) {
                shipment
            } else {
                shipment.copy(confirmedByMolAt = effective)
            },
            items = items,
            photos = photos,
        )
    }

    /**
     * Есть ли в очереди upsert-мутация, которая везёт на сервер непустое время
     * подтверждения. Битый payload (сменился формат, обрезанная строка) — не
     * повод держать локальное значение: считаем, что такой мутации нет.
     */
    private suspend fun hasQueuedConfirm(
        entityType: String,
        entityId: String,
        extract: (String) -> String?,
    ): Boolean = mutationDao.findFor(entityType, entityId).any { m ->
        if (m.operation != OPERATION_UPSERT) return@any false
        val payload = m.payloadJson ?: return@any false
        runCatching { extract(payload) }.getOrNull() != null
    }

    private companion object {
        const val ENTITY_DELIVERY = "delivery"
        const val ENTITY_SHIPMENT = "shipment"
        const val OPERATION_UPSERT = "upsert"
    }
}
