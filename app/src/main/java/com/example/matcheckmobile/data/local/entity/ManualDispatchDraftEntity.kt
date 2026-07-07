package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Незавершённый «Ручной вынос» — отгрузка без УПД и автотранспорта
 * (зеркало [ManualEntryDraftEntity] для shipment'ов).
 *
 * Отличие — поле [shipmentPurpose] («Тип отгрузки»), которое на finalize
 * уходит в `shipments.purpose`. По «Завершить» превращается в shipment со
 * статусом `confirmed_mol`, после чего черновик удаляется.
 */
@Entity(
    tableName = "manual_dispatch_drafts",
    indices = [
        Index(value = ["siteId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class ManualDispatchDraftEntity(
    @PrimaryKey val localDraftId: String,
    val siteId: String,
    val documentPhotoPathsJson: String,
    val cargoPhotoPathsJson: String,
    val manualUpdText: String,
    val materialsJson: String,
    val commentText: String,
    /** «Тип отгрузки» — одно из SHIPMENT_PURPOSE_OPTIONS. null = не выбрано. */
    val shipmentPurpose: String?,
    /** ОС — чекбокс «основные средства». Default false. */
    val isAssets: Boolean = false,
    /** Момент создания черновика («Создать вынос»). Точка отсчёта таймера в списке. */
    val createdAt: Long,
    val updatedAt: Long,
)
