package com.example.matcheckmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SyncStatus

@Entity(
    tableName = "material_operations",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptSessionEntity::class,
            parentColumns = ["localId"],
            childColumns = ["sessionLocalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["siteId"]),
        Index(value = ["createdAtLocal"]),
        Index(value = ["sessionLocalId"]),
    ],
)
data class MaterialOperationEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val type: OperationType,
    val siteId: String,
    val materialId: String?,
    val materialNameRaw: String,
    val quantity: Double,
    val unit: String,
    val userId: String,
    val deviceId: String,
    val vehicleNumber: String?,
    val driverName: String?,
    val comment: String?,
    val createdAtLocal: Long,
    val receivedAtServer: Long?,
    @ColumnInfo(name = "syncStatus") val syncStatus: SyncStatus,
    val lastSyncError: String?,
    val idempotencyKey: String,
    val sessionLocalId: String?,
)
