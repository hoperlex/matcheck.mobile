package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_counterparties",
    indices = [
        Index(value = ["isSupplier"]),
        Index(value = ["isContractor"]),
    ],
)
data class RemoteCounterpartyEntity(
    @PrimaryKey val id: String,
    val inn: String,
    val kpp: String?,
    val name: String,
    val address: String?,
    val isSelf: Boolean,
    val isSupplier: Boolean,
    val isCustomer: Boolean,
    val isContractor: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(tableName = "remote_materials")
data class RemoteMaterialEntity(
    @PrimaryKey val id: String,
    val code: String?,
    val name: String,
    val unit: String,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "remote_sites",
    indices = [Index(value = ["code"], unique = true)],
)
data class RemoteSiteEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val fullName: String?,
    val address: String?,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Справочник кодов статусов с UI-метаданными (label, color).
 * entityType = 'delivery' | 'shipment' | … — чтобы один и тот же code
 * мог иметь разные label для разных сущностей.
 */
@Entity(
    tableName = "remote_statuses",
    indices = [Index(value = ["entityType", "code"], unique = true)],
)
data class RemoteStatusEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val code: String,
    val label: String,
    val color: String?,
    val sortOrder: Int,
)
