package com.example.matcheckmobile.data.remote.api.dto

import kotlinx.serialization.Serializable

/**
 * DTO для POST /api/v1/sync/reconcile (read-only сверка планшет ↔ сервер).
 * Имена полей строго совпадают с серверным контрактом (packages/contracts/
 * src/sync.ts). Клиент шлёт локальные id+version, сервер отвечает
 * расхождениями. M4a обрабатывает только missingOnClient/staleOnClient
 * (докачка с сервера); missingOnServer на этом шаге игнорируется (M4b).
 */
@Serializable
data class ReconcileItemDto(
    val id: String,
    val version: Int,
)

@Serializable
data class ReconcileRequestDto(
    val deliveries: List<ReconcileItemDto>,
    val shipments: List<ReconcileItemDto>,
    val sourceDocuments: List<ReconcileItemDto>,
    /**
     * То же, что query-параметр `capabilities` у GET /sync, — здесь в теле,
     * потому что reconcile POST.
     *
     * Обязательно именно в обоих местах. Разъедься они — дельта прислала бы
     * tombstone на скрытый документ, а сверка через минуту вернула бы его
     * обратно, и скрытие не пережило бы одну синхронизацию.
     */
    val capabilities: String? = null,
)

@Serializable
data class ReconcileMissingOnClientDto(
    val id: String,
    val version: Int,
    val updatedAt: String,
)

@Serializable
data class ReconcileStaleDto(
    val id: String,
    val serverVersion: Int,
)

@Serializable
data class ReconcilePerTypeDto(
    val missingOnClient: List<ReconcileMissingOnClientDto> = emptyList(),
    val staleOnClient: List<ReconcileStaleDto> = emptyList(),
    val missingOnServer: List<String> = emptyList(),
)

@Serializable
data class ReconcileResponseDto(
    val serverNow: String,
    val deliveries: ReconcilePerTypeDto,
    val shipments: ReconcilePerTypeDto,
    val sourceDocuments: ReconcilePerTypeDto,
)
