package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_queue",
    indices = [Index(value = ["targetLocalId"])],
)
data class SyncQueueEntity(
    @PrimaryKey val localId: String,
    val targetType: String,
    val targetLocalId: String,
    val attempts: Int,
    val lastAttemptAt: Long?,
    val nextAttemptAt: Long?,
    val lastError: String?,
    val createdAt: Long,
) {
    companion object {
        const val TARGET_OPERATION = "OPERATION"
        const val TARGET_ATTACHMENT = "ATTACHMENT"
    }
}
