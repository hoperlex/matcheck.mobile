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
    /**
     * Справочник единиц измерения для дропдауна «Ед.» в модалке
     * материалов. Default emptyList() — старый сервер поле не присылает,
     * мобила работает по-прежнему с ручным вводом. См. серверный sync.ts.
     */
    val units: List<UnitDto> = emptyList(),
    val deletedIds: SyncDeletedIds = SyncDeletedIds(),
    /**
     * Почему документ ушёл из выдачи: `deleted` — удалён окончательно,
     * `hidden` — временно скрыт (переразбор, неполная машина, перенос объекта).
     * Необязательное: сервер до выпуска 1 группового протокола его не шлёт.
     * deletedIds остаётся прежним — это пояснение к нему, а не замена.
     */
    val deletedReasons: SyncDeletedReasons? = null,
    /**
     * Есть продолжение — значение передаётся следующим запросом параметром
     * `pageToken`. null или отсутствие поля означает «страница последняя».
     *
     * Приходит только в групповом режиме (клиент заявил capability
     * `source_groups_v1`). Старый сервер поля не присылает, default null —
     * тогда работает прежнее определение «есть ли ещё» по размеру страниц.
     *
     * Главное правило потребителя: основной курсор (`cursor`) сохраняется
     * ТОЛЬКО после последней страницы. Сдвинув его раньше, клиент потеряет
     * хвост — ровно та ошибка, ради которой пагинацию и переделывали.
     */
    val nextPageToken: String? = null,
)

@Serializable
data class SyncDeletedReasons(
    /** id документа → `deleted` | `hidden`. */
    val sourceDocuments: Map<String, String> = emptyMap(),
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
