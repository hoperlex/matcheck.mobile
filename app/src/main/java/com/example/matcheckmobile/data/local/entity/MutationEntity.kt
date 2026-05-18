package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Push-queue для пер-сущностных мутаций (заменит [SyncQueueEntity] в Этапе 3).
 *
 * Поле [payloadJson] хранит сериализованный upsert-DTO (DeliveryUpsertRequest
 * или ShipmentUpsertRequest) — на момент enqueue. При re-try Sync engine
 * подменит `baseVersion` на актуальный (после server_win/local_win/merge).
 *
 * [conflictPending] — флаг 409: до получения UI-резолюции push не повторяется.
 */
@Entity(
    tableName = "mutations",
    indices = [
        Index(value = ["entityType", "entityId"]),
        Index(value = ["conflictPending"]),
        Index(value = ["nextAttemptAt"]),
    ],
)
data class MutationEntity(
    @PrimaryKey val id: String,
    val entityType: String, // 'delivery' | 'shipment'
    val operation: String,  // 'upsert' | 'delete' | 'mark_deletion' | 'unmark_deletion'
    val entityId: String,
    val baseVersion: Int?,
    val payloadJson: String?,
    val attempts: Int,
    val nextAttemptAt: Long?,
    val lastError: String?,
    val conflictPending: Boolean,
    val createdAt: Long,
)
