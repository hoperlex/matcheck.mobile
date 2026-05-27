package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Зеркало [Stage2DeliveryRow] для отгрузки. */
data class Stage2ShipmentRow(
    val shipment: RemoteShipmentEntity,
    val titleText: String,
    val subtitleText: String,
    val hasDraft: Boolean = false,
)

data class Stage2ShipmentGroup(
    val contractorName: String,
    val rows: List<Stage2ShipmentRow>,
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"

/**
 * Зеркало [Stage2ListViewModel] для отгрузки. Статус `shipped` — отгружено,
 * ждёт подтверждения МОЛ; `confirmed_mol` — финализировано (уходит из списка).
 */
class DispatchStage2ListViewModel(container: AppContainer) : ViewModel() {

    init {
        viewModelScope.launch {
            container.shipmentRepository.observeByStatuses(STAGE2_STATUSES)
                .map { list ->
                    list.flatMap { RemoteMappers.decodeIdList(it.sourceDocumentIdsJson) }.toSet()
                }
                .distinctUntilChanged()
                .onEach { ids -> container.sourceDocumentBackfillService.ensureCached(ids) }
                .collect { }
        }
    }

    val groups: StateFlow<List<Stage2ShipmentGroup>> = combine(
        container.shipmentRepository.observeByStatuses(STAGE2_STATUSES),
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteCounterpartyDao().observeAll(),
        container.shipmentStage2DraftRepository.observeIds(),
    ) { shipments, sourceDocs, counterparties, draftIds ->
        val cpById = counterparties.associateBy { it.id }
        val docById = sourceDocs.associateBy { it.id }
        val draftIdSet = draftIds.toSet()

        val pairs = shipments.map { s ->
            val attachedIds = RemoteMappers.decodeIdList(s.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }

            val updNumbers = attachedDocs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
            val updNumberText = when {
                updNumbers.isNotEmpty() -> buildUpdSummary(updNumbers)
                else -> extractManualUpd(s.comment)?.takeIf { it.isNotBlank() } ?: "—"
            }
            val titleText = "УПД $updNumberText"

            val supplierName = attachedDocs.firstOrNull()?.let { doc ->
                doc.supplierName ?: doc.supplierId?.let { id -> cpById[id]?.name }
            }
            val subtitleText = "Поставщик: ${supplierName ?: "—"}"

            // Для shipment «подрядчик-группировка» — это получатель: либо
            // recipientId/recipientMolId УПД, либо receiverCounterpartyId
            // shipment'а. Пока берём receiverCounterpartyId как основной ключ.
            val contractorName = s.receiverCounterpartyId?.let { cpById[it]?.name }
                ?: attachedDocs.firstOrNull()?.contractorName

            val row = Stage2ShipmentRow(
                shipment = s,
                titleText = titleText,
                subtitleText = subtitleText,
                hasDraft = s.id in draftIdSet,
            )
            val groupKey = contractorName?.takeIf { it.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL
            row to groupKey
        }

        pairs.groupBy({ it.second }, { it.first })
            .toList()
            .sortedWith(
                compareBy(
                    { it.first == UNKNOWN_CONTRACTOR_LABEL },
                    { it.first.lowercase() },
                ),
            )
            .map { (contractor, items) -> Stage2ShipmentGroup(contractor, items) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        const val UPD_SUMMARY_MAX_INLINE = 2
        val STAGE2_STATUSES = listOf("shipped")
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")

        fun buildUpdSummary(numbers: List<String>): String {
            if (numbers.size <= UPD_SUMMARY_MAX_INLINE) return numbers.joinToString(", ")
            val head = numbers.take(UPD_SUMMARY_MAX_INLINE).joinToString(", ")
            return "$head +${numbers.size - UPD_SUMMARY_MAX_INLINE}"
        }

        fun extractManualUpd(comment: String?): String? {
            if (comment.isNullOrBlank()) return null
            return MANUAL_UPD_REGEX.find(comment)?.groupValues?.getOrNull(1)?.trim()
        }
    }
}
