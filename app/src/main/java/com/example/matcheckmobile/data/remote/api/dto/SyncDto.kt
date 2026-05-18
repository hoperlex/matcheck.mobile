package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * GET /api/v1/sync?since={ISO-8601}&windowDays={N}. См. MOBILE_API.md
 * «Дельта-синхронизация». Все массивы могут быть пустыми; cursor — серверное
 * время для следующего запроса.
 */
@Serializable
data class SyncDeltaResponse(
    val cursor: String,
    val serverNow: String,
    val deliveries: List<DeliveryDto> = emptyList(),
    val shipments: List<ShipmentDto> = emptyList(),
    val sourceDocuments: List<SourceDocumentDto> = emptyList(),
    val counterparties: List<CounterpartyDto> = emptyList(),
    val materials: List<MaterialDto> = emptyList(),
    val sites: List<SiteDto> = emptyList(),
    val statuses: List<StatusDto> = emptyList(),
    val deletedIds: SyncDeletedIds = SyncDeletedIds(),
)

@Serializable
data class SyncDeletedIds(
    val deliveries: List<String> = emptyList(),
    val shipments: List<String> = emptyList(),
    val sourceDocuments: List<String> = emptyList(),
)

@Serializable
data class SseEventPayload(
    val type: String,
    val entityId: String? = null,
    val ts: String,
)
