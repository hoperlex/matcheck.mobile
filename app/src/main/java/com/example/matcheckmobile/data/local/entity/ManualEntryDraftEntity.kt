package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Незавершённый «Ручной внос» — приёмка без УПД и автотранспорта, ещё не
 * отправленная в [com.example.matcheckmobile.data.repository.DeliveryRepository].
 *
 * В отличие от [Stage1DraftEntity] (черновик-оверлей поверх конкретной УПД),
 * это самостоятельная сущность: инспектор создаёт её кнопкой «Создать внос»
 * на [ManualEntryListScreen], дозаполняет фото/материалы и по «Завершить»
 * она превращается в delivery со статусом `confirmed_mol` (после чего черновик
 * удаляется). До «Завершить» живёт только локально и на сервер не уходит.
 *
 * `siteId` — объект, на котором создан черновик; список фильтруется по нему
 * (fail-closed), а finalize отправляет запись именно на этот объект.
 */
@Entity(
    tableName = "manual_entry_drafts",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class ManualEntryDraftEntity(
    @PrimaryKey val localDraftId: String,
    val siteId: String,
    val documentPhotoPathsJson: String,
    val cargoPhotoPathsJson: String,
    val manualUpdText: String,
    val materialsJson: String,
    val commentText: String,
    /** ОС — чекбокс «основные средства». Default false. */
    val isAssets: Boolean = false,
    /** Момент создания черновика («Создать внос»). Точка отсчёта таймера в списке. */
    val createdAt: Long,
    val updatedAt: Long,
)
