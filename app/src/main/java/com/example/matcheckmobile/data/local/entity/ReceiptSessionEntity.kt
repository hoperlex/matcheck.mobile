package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.matcheckmobile.domain.model.SyncStatus

@Entity(
    tableName = "receipt_sessions",
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["startedAt"]),
    ],
)
data class ReceiptSessionEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val siteId: String,
    val userId: String,
    val deviceId: String,
    val supplierLocalId: String?,
    val vehicleNumber: String,
    val sourceDocumentLocalId: String?,
    val comment: String?,
    val startedAt: Long,
    val finalizedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncError: String?,
    val idempotencyKey: String,
)
