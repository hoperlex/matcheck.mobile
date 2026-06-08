package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Зеркало [Stage1DraftEntity] для отгрузки. Хранит незавершённую форму
 * «1 Этап» (Выезд): фото + поля. Сохраняется автоматически при наличии
 * хотя бы одного фото; удаляется при finalize или когда все фото стёрты.
 *
 * `updId == null` — отгрузка без выбранной УПД («Создать отгрузку»).
 * UNIQUE индекс по updId — один draft на УПД.
 */
@Entity(
    tableName = "shipment_stage1_drafts",
    indices = [
        Index(value = ["updId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class ShipmentStage1DraftEntity(
    @PrimaryKey val localDraftId: String,
    val updId: String?,
    val documentPhotoPathsJson: String,
    val cargoPhotoPathsJson: String,
    val vehicleTypeCode: String?,
    val materialsJson: String,
    val commentText: String,
    val licensePlate: String,
    val manualUpdText: String,
    // Выбор инспектора в dropdown «Тип отгрузки» на empty-draft форме
    // («Новая отгрузка», updId=null). Значения: «Вывоз материала»,
    // «Перемещение на объект», «Вывоз мусора», «Другое». При finalize
    // префиксом добавляется в comment («Тип: …»). NULL — не выбрано.
    val shipmentPurpose: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
