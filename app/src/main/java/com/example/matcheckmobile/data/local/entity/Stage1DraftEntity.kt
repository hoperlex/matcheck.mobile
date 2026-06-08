package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Незавершённая 1 Этап-приёмка: ввод и фото пользователя, ещё не отправленные
 * в DeliveryRepository.upsert. Сохраняется автоматически при наличии хотя бы
 * одного фото; удаляется при finalize или когда пользователь стирает все фото.
 *
 * `updId == null` — пустая приёмка (создаётся через «Создать приёмку» внизу
 * экрана 1 Этап). У такого draft нет соответствующей карточки УПД в списке,
 * поэтому отображаем его как псевдо-карточку в группе «Подрядчик не указан».
 *
 * UNIQUE индекс по updId гарантирует один draft на УПД (NULL не считается
 * уникальным в SQLite — пустых drafts может быть несколько).
 */
@Entity(
    tableName = "stage1_drafts",
    indices = [
        Index(value = ["updId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class Stage1DraftEntity(
    @PrimaryKey val localDraftId: String,
    val updId: String?,
    val documentPhotoPathsJson: String,
    val cargoPhotoPathsJson: String,
    val vehicleTypeCode: String?,
    val materialsJson: String,
    val commentText: String,
    val licensePlate: String,
    val manualUpdText: String,
    /**
     * Транзит — чекбокс инспектора на 1 этапе. Сохраняется в draft, чтобы
     * пережить свернуть/открыть. На finalize отправляется в server.
     * Default false (миграция Room 20→21).
     */
    val inTransit: Boolean = false,
    /** Момент создания draft = когда было добавлено первое фото («Начато»). */
    val createdAt: Long,
    val updatedAt: Long,
)
