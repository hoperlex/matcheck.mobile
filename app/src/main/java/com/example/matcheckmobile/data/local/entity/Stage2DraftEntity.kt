package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальные правки формы 2 Этапа поверх серверного снимка delivery.
 * Хранится одна запись на deliveryId (PRIMARY KEY).
 *
 * Создаётся автоматически при первом отклонении формы от server-snapshot
 * (правка qty/наименования материала, новая позиция, изменение комментария,
 * выбор типа транспорта, добавление фото). Удаляется при finalize 2 Этапа
 * или когда пользователь откатил все правки.
 */
@Entity(
    tableName = "stage2_drafts",
    indices = [Index(value = ["updatedAt"])],
)
data class Stage2DraftEntity(
    @PrimaryKey val deliveryId: String,
    val documentPhotoPathsJson: String,
    val vehiclePhotoPathsJson: String,
    val vehicleTypeCode: String?,
    val materialsJson: String,
    val editedIndexesJson: String,
    val commentText: String,
    /** Момент первой правки (= появление draft, «Начато»). */
    val createdAt: Long,
    val updatedAt: Long,
)
