package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Локальные мета-поля по отгрузке — зеркало [DeliveryLocalMetaEntity].
 * Хранят то, что не передаётся на сервер и не должно затираться при /sync.
 *
 * Сейчас содержит только [vehicleTypeCode] — выбор типа транспорта на 1
 * Этапе отгрузки (Ларгус/Газель/Грузовик/Фура). Без локального хранилища
 * выбор пропадал при открытии 2 Этапа: серверный shipment в DTO такого
 * поля не знает.
 *
 * FK на remote_shipments с CASCADE: запись чистится автоматически при
 * физическом удалении отгрузки.
 */
@Entity(
    tableName = "shipment_local_meta",
    foreignKeys = [
        ForeignKey(
            entity = RemoteShipmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["shipmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ShipmentLocalMetaEntity(
    @PrimaryKey val shipmentId: String,
    val vehicleTypeCode: String?,
)
