package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * Inbox-список ожидаемых УПД. Источник — серверная таблица
 * `remote_source_documents`, заполняемая через [SyncRepository.pullDelta].
 *
 * Сервер отдаёт inspector_kpp только не привязанные к приёмке/отгрузке УПД,
 * но дельта-sync не удаляет локально те, что стали привязанными после
 * первичной загрузки (их нет ни в response, ни в `deletedIds`). Чтобы UI
 * не показывал «зомби» из устаревшего кэша, фильтруем по локально известным
 * привязкам Delivery + Shipment.
 *
 * Делим на «Сегодня» / «Будущие» по `expectedDate` (формат сервера
 * 'YYYY-MM-DD'). Сегодня — `expectedDate == LocalDate.now()`,
 * иначе (другая дата или null) — Будущие.
 */
data class IntakeUpdRow(
    val document: RemoteSourceDocumentEntity,
    /** Имя поставщика. На сервере приходит в `supplierName` готовое; counterparties используем как fallback. */
    val supplierName: String?,
    /** Имя подрядчика. Аналогично supplierName: серверное поле + fallback на counterparties. */
    val contractorName: String?,
)

/** Группа УПД по подрядчику. Используется для секций в [IntakeUpdSelectScreen]. */
data class IntakeUpdGroup(
    val contractorName: String,
    val rows: List<IntakeUpdRow>,
)

data class IntakeUpdGroupsState(
    val today: List<IntakeUpdGroup> = emptyList(),
    val future: List<IntakeUpdGroup> = emptyList(),
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"

class IntakeUpdSelectViewModel(container: AppContainer) : ViewModel() {

    val state: StateFlow<IntakeUpdGroupsState> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteCounterpartyDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
    ) { docs, cps, deliveryAttachedJsons, shipmentAttachedJsons ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val byCounterpartyId = cps.associateBy { it.id }
        val today = LocalDate.now().toString()
        val todayRows = mutableListOf<IntakeUpdRow>()
        val futureRows = mutableListOf<IntakeUpdRow>()
        for (d in docs) {
            if (d.id in attachedIds) continue
            val row = IntakeUpdRow(
                document = d,
                supplierName = d.supplierName
                    ?: d.supplierId?.let { byCounterpartyId[it]?.name },
                contractorName = d.contractorName
                    ?: d.contractorId?.let { byCounterpartyId[it]?.name },
            )
            if (d.expectedDate == today) todayRows.add(row) else futureRows.add(row)
        }
        IntakeUpdGroupsState(
            today = groupByContractor(todayRows),
            future = groupByContractor(futureRows),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeUpdGroupsState(),
    )

    private fun groupByContractor(rows: List<IntakeUpdRow>): List<IntakeUpdGroup> =
        rows.groupBy { it.contractorName?.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL }
            .toList()
            .sortedWith(
                compareBy(
                    { it.first == UNKNOWN_CONTRACTOR_LABEL },
                    { it.first.lowercase() },
                ),
            )
            .map { (contractor, items) -> IntakeUpdGroup(contractor, items) }
}
