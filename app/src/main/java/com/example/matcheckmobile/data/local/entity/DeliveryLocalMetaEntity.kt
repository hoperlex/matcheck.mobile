package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Локальные мета-поля по приёмке, которых нет в DeliveryDto на сервере и
 * которые не должны затираться при /sync (server-snapshot живёт в
 * remote_deliveries, эта таблица — отдельная).
 *
 * Сейчас содержит только [vehicleTypeCode] — выбор типа транспорта на
 * мобиле (Ларгус/Газель/Грузовик/Фура). На сервер не уходит.
 *
 * FK на remote_deliveries с CASCADE — при физическом удалении приёмки
 * запись чистится автоматически.
 */
@Entity(
    tableName = "delivery_local_meta",
    foreignKeys = [
        ForeignKey(
            entity = RemoteDeliveryEntity::class,
            parentColumns = ["id"],
            childColumns = ["deliveryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeliveryLocalMetaEntity(
    @PrimaryKey val deliveryId: String,
    val vehicleTypeCode: String?,
)
