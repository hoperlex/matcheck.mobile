package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.ShipmentStage1DraftEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * Зеркало [IntakeUpdSelectViewModel] для отгрузки. Фильтр УПД по
 * `direction='outbound'`; источник drafts — `shipmentStage1DraftRepository`.
 * Группировка/вкладки/«Начато» работают так же.
 */
data class DispatchUpdRow(
    val document: RemoteSourceDocumentEntity?,
    val supplierName: String?,
    val contractorName: String?,
    val draftId: String? = null,
)

data class DispatchUpdGroup(val contractorName: String, val rows: List<DispatchUpdRow>)

data class DispatchUpdGroupsState(
    val today: List<DispatchUpdGroup> = emptyList(),
    val future: List<DispatchUpdGroup> = emptyList(),
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"
private const val MANUAL_GROUP_LABEL = "Созданы вручную"

class DispatchUpdSelectViewModel(container: AppContainer) : ViewModel() {

    val state: StateFlow<DispatchUpdGroupsState> = combine(
        combine(
            container.database.remoteSourceDocumentDao().observeAll(),
            container.database.remoteCounterpartyDao().observeAll(),
            container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
            container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
            container.shipmentStage1DraftRepository.observeAll(),
            ::DispatchUpdSources,
        ),
        dayTicker,
        container.tokenStorage.state,
    ) { src, today, tokenSnapshot ->
        // См. IntakeUpdSelectViewModel: defense-in-depth siteId-фильтр от
        // stale-записей чужого объекта в локальной Room. Fail-closed при
        // пустом siteId — лучше пусто, чем чужое.
        val currentSiteId = tokenSnapshot.siteId
        if (currentSiteId.isNullOrBlank()) return@combine DispatchUpdGroupsState()

        val (docs, cps, deliveryAttachedJsons, shipmentAttachedJsons, drafts) = src
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val byCounterpartyId = cps.associateBy { it.id }
        val draftsByUpdId: Map<String, String> = drafts
            .mapNotNull { d -> d.updId?.let { it to d.localDraftId } }
            .toMap()
        val emptyDrafts: List<ShipmentStage1DraftEntity> = drafts.filter { it.updId == null }

        // Direction + attachedIds + siteId в одном месте. См. UpdSelectFilter.
        val ownDocs = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = currentSiteId,
            direction = "outbound",
            attachedIds = attachedIds,
        )

        val todayRows = mutableListOf<DispatchUpdRow>()
        val futureRows = mutableListOf<DispatchUpdRow>()
        for (d in ownDocs) {
            val draftId = draftsByUpdId[d.id]
            val row = DispatchUpdRow(
                document = d,
                supplierName = d.supplierName ?: d.supplierId?.let { byCounterpartyId[it]?.name },
                contractorName = d.contractorName ?: d.contractorId?.let { byCounterpartyId[it]?.name },
                draftId = draftId,
            )
            val bucket = when {
                draftId != null -> todayRows
                d.expectedDate == today -> todayRows
                else -> futureRows
            }
            bucket.add(row)
        }
        for (draft in emptyDrafts) {
            todayRows.add(
                DispatchUpdRow(
                    document = null,
                    supplierName = null,
                    contractorName = null,
                    draftId = draft.localDraftId,
                ),
            )
        }
        DispatchUpdGroupsState(
            today = groupByContractor(todayRows),
            future = groupByContractor(futureRows),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DispatchUpdGroupsState(),
    )

    private fun groupByContractor(rows: List<DispatchUpdRow>): List<DispatchUpdGroup> {
        val (manualRows, realRows) = rows.partition { it.document == null }
        val realGroups = realRows
            .groupBy { it.contractorName?.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL }
            .toList()
            .sortedWith(
                compareBy(
                    { it.first == UNKNOWN_CONTRACTOR_LABEL },
                    { it.first.lowercase() },
                ),
            )
            .map { (contractor, items) -> DispatchUpdGroup(contractor, items) }
        return if (manualRows.isEmpty()) realGroups
            else realGroups + DispatchUpdGroup(MANUAL_GROUP_LABEL, manualRows)
    }

    private data class DispatchUpdSources(
        val docs: List<RemoteSourceDocumentEntity>,
        val counterparties: List<com.example.matcheckmobile.data.local.entity.RemoteCounterpartyEntity>,
        val deliveryAttachedJsons: List<String>,
        val shipmentAttachedJsons: List<String>,
        val drafts: List<ShipmentStage1DraftEntity>,
    )

    companion object {
        private val dayTicker = flow {
            while (true) {
                emit(LocalDate.now().toString())
                delay(60_000L)
            }
        }.distinctUntilChanged()
    }
}
