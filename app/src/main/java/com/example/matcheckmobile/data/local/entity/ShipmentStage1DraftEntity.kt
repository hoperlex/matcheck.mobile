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
 * UNIQUE индекс по updId — один draft на УПД, по groupId — один на машину.
 */
@Entity(
    tableName = "shipment_stage1_drafts",
    indices = [
        Index(value = ["updId"], unique = true),
        Index(value = ["groupId"], unique = true),
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
    /**
     * Транзит — чекбокс инспектора на 1 этапе. Сохраняется в draft, чтобы
     * пережить свернуть/открыть. На finalize отправляется в server.
     * Default false (миграция Room 20→21).
     */
    val inTransit: Boolean = false,
    /**
     * ОС — чекбокс «основные средства» рядом с Транзитом. Сохраняется в
     * draft, чтобы пережить свернуть/открыть. На finalize отправляется
     * в server. Default false (миграция Room 21→22).
     */
    val isAssets: Boolean = false,
    /** «Машина», см. [Stage1DraftEntity.groupId]. */
    val groupId: String? = null,
    /** Снимок состава машины на момент загрузки формы, см. [Stage1DraftEntity.loadedDocIdsJson]. */
    val loadedDocIdsJson: String = "[]",
    /** Версия состава группы на момент загрузки формы. */
    val loadedGroupRevision: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
