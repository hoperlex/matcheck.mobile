package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Серверный снапшот единицы измерения (справочник `units` на сервере).
 *
 * Используется как whitelist для дропдауна «Ед.» в модалке материалов
 * на 1-м и 2-м этапе. Приходит через /sync в массиве `units` — мобила
 * сохраняет всё активное и рендерит как dropdown. Меняется редко
 * (~20 записей), отдаётся целиком без дельта-курсора.
 *
 * `code` — компактный код («шт», «кг», «м³»), который сохраняется в
 * `deliveryItems.unit` / `shipmentItems.unit` как text. Это исторический
 * формат, на сервере FK на справочник нет — legacy-значения не теряются.
 *
 * `okeiCode` — общероссийский код (для будущей сверки с УПД). Для
 * пользовательских единиц («бухта», «мешок») nullable.
 */
@Entity(tableName = "remote_units")
data class RemoteUnitEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val okeiCode: String?,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
)
