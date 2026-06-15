package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.sourceDocTitlePrefix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Архив завершённых отгрузок за последние 7 дней — зеркало
 * [ArchiveIntakeListViewModel] для outbound-стороны.
 *
 * Статус для архива — `confirmed_mol`: сюда попадают и пара 1+2 этап, и
 * «Ручной вынос» (он пишется сразу как confirmed_mol). Точка отсчёта для
 * окна и группировки по дате — лучшее из доступных timestamp'ов:
 *   shippedAt → confirmedByMolAt → updatedAt
 * `shippedAt` ставится в `DispatchStage1FormViewModel.finalizeStage1` и
 * означает «момент выезда» — это симметрия с `arrivedAt` на стороне приёмки.
 */
data class ArchiveDispatchRow(
    val shipment: RemoteShipmentEntity,
    val updNumber: String,
    val vehiclePlate: String,
    /** Время выезда — finalize Stage1 (1 Этап) на устройстве инспектора. */
    val shippedAtMs: Long?,
    /** Время подтверждения МОЛ (2 Этап). null если ещё не подтверждено. */
    val confirmedAtMs: Long?,
)

data class ArchiveDispatchDayGroup(
    val dateLabel: String,
    val dayStartMs: Long,
    val rows: List<ArchiveDispatchRow>,
)

class ArchiveDispatchListViewModel(container: AppContainer) : ViewModel() {

    init {
        // Backfill docNumber по тем же sourceDocs, что и DispatchStage2ListViewModel:
        // /sync для inspector_kpp отфильтровывает привязанные УПД, без backfill
        // в карточке архива будет «УПД —».
        viewModelScope.launch {
            container.shipmentRepository.observeByStatuses(ARCHIVE_STATUSES)
                .map { list ->
                    list.flatMap { RemoteMappers.decodeIdList(it.sourceDocumentIdsJson) }.toSet()
                }
                .distinctUntilChanged()
                .onEach { ids -> container.sourceDocumentBackfillService.ensureCached(ids) }
                .collect { }
        }
    }

    val groups: StateFlow<List<ArchiveDispatchDayGroup>> = combine(
        container.shipmentRepository.observeByStatuses(ARCHIVE_STATUSES),
        container.database.remoteSourceDocumentDao().observeAll(),
    ) { shipments, sourceDocs ->
        val docById = sourceDocs.associateBy { it.id }
        val nowMs = System.currentTimeMillis()
        val windowStartMs = nowMs - WINDOW_DURATION.toMillis()
        val zone = ZoneId.systemDefault()

        val rows = shipments.mapNotNull { s ->
            val shippedAtMs = parseMs(s.shippedAt)
            val confirmedAtMs = parseMs(s.confirmedByMolAt)
            val updatedAtMs = parseMs(s.updatedAt)
            val anchorMs = shippedAtMs ?: confirmedAtMs ?: updatedAtMs ?: return@mapNotNull null
            if (anchorMs < windowStartMs) return@mapNotNull null

            val attachedIds = RemoteMappers.decodeIdList(s.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }
            val updNumbers = attachedDocs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
            val manualFromComment = extractManualUpd(s.comment)?.takeIf { it.isNotBlank() }
            // Fallback в порядке: docNumber → ручной номер из comment → «Без УПД».
            // «Без УПД» — для отгрузок, оформленных без привязки документа
            // (например, «Ручной вынос» или transfer/writeoff из админки).
            val updText = when {
                updNumbers.isNotEmpty() -> buildUpdSummary(updNumbers)
                manualFromComment != null -> manualFromComment
                else -> null
            }
            val prefix = attachedDocs.firstOrNull()?.kind?.let(::sourceDocTitlePrefix) ?: "УПД"
            val title = if (updText != null) "$prefix $updText" else "Без УПД"

            val plate = s.vehiclePlate?.takeIf { it.isNotBlank() } ?: "—"

            ArchiveDispatchRow(
                shipment = s,
                updNumber = title,
                vehiclePlate = plate,
                shippedAtMs = shippedAtMs,
                confirmedAtMs = confirmedAtMs,
            ) to anchorMs
        }

        rows
            .groupBy { (_, anchor) ->
                Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
            }
            .map { (date, list) ->
                val sorted = list
                    .sortedByDescending { it.second }
                    .map { it.first }
                ArchiveDispatchDayGroup(
                    dateLabel = date.format(DATE_LABEL_FMT),
                    dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    rows = sorted,
                )
            }
            .sortedByDescending { it.dayStartMs }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        const val UPD_SUMMARY_MAX_INLINE = 2
        val ARCHIVE_STATUSES = listOf("confirmed_mol")
        val WINDOW_DURATION: Duration = Duration.ofDays(7)
        val DATE_LABEL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")

        fun parseMs(iso: String?): Long? =
            iso?.takeIf { it.isNotBlank() }?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }

        fun extractManualUpd(comment: String?): String? {
            if (comment.isNullOrBlank()) return null
            return MANUAL_UPD_REGEX.find(comment)?.groupValues?.getOrNull(1)?.trim()
        }

        fun buildUpdSummary(numbers: List<String>): String {
            if (numbers.size <= UPD_SUMMARY_MAX_INLINE) return numbers.joinToString(", ")
            val head = numbers.take(UPD_SUMMARY_MAX_INLINE).joinToString(", ")
            return "$head +${numbers.size - UPD_SUMMARY_MAX_INLINE}"
        }
    }
}
