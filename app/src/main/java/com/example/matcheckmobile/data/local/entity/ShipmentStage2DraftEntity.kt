package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Зеркало [Stage2DraftEntity] для отгрузки. Локальные правки формы 2 Этапа
 * поверх серверного снимка shipment.
 */
@Entity(
    tableName = "shipment_stage2_drafts",
    indices = [Index(value = ["updatedAt"])],
)
data class ShipmentStage2DraftEntity(
    @PrimaryKey val shipmentId: String,
    val documentPhotoPathsJson: String,
    val vehiclePhotoPathsJson: String,
    val vehicleTypeCode: String?,
    val materialsJson: String,
    val editedIndexesJson: String,
    val commentText: String,
    val createdAt: Long,
    val updatedAt: Long,
)
