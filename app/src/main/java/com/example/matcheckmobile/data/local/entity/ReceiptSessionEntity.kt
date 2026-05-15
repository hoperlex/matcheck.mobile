package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.matcheckmobile.domain.model.SessionKind
import com.example.matcheckmobile.domain.model.SyncStatus

@Entity(
    tableName = "receipt_sessions",
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["startedAt"]),
        Index(value = ["kind"]),
    ],
)
data class ReceiptSessionEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val kind: SessionKind,
    val siteId: String,
    val userId: String,
    val deviceId: String,
    val supplierLocalId: String?,
    val contractorLocalId: String?,
    val vehicleNumber: String,
    val vehicleTypeCode: String?,
    val volumeM3: Double?,
    val massKg: Double?,
    val sourceDocumentLocalId: String?,
    val comment: String?,
    val startedAt: Long,
    val finalizedAt: Long?,
    val completedAt: Long?,
    val confirmedByMol: Boolean,
    val syncStatus: SyncStatus,
    val lastSyncError: String?,
    val idempotencyKey: String,
)
