package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.ShipmentStage1DraftEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.BusinessTime
import com.example.matcheckmobile.domain.model.draftGroupKey
import com.example.matcheckmobile.domain.model.groupDocsByMachine
import com.example.matcheckmobile.domain.model.groupKeyOf
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
    val consigneeName: String?,
    /**
     * Покупатель (графа 6) — вторая ступень подписи строки. Подрядчика в
     * списках УПД не показываем вовсе: на портале он скрыт из таблиц
     * документов, и подпись «Подрядчик» расходилась бы с тем, что видит
     * менеджер.
     */
    val buyerName: String?,
    val draftId: String? = null,
    /** «Машина», см. [IntakeUpdRow.groupId]. */
    val groupId: String? = null,
    /** Документы строки в порядке sortGroupDocs, см. [IntakeUpdRow.documents]. */
    val documents: List<RemoteSourceDocumentEntity> = emptyList(),
)

/** См. [IntakeUpdGroup]: [key] — для Compose, [displayName] — на экран. */
data class DispatchUpdGroup(
    val key: String,
    val displayName: String,
    val rows: List<DispatchUpdRow>,
)

data class DispatchUpdGroupsState(
    val today: List<DispatchUpdGroup> = emptyList(),
    val future: List<DispatchUpdGroup> = emptyList(),
)

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
        val docsById = docs.associateBy { it.id }
        // Совместимый ключ черновика — см. IntakeUpdSelectViewModel.
        val draftsByGroupKey: Map<String, String> = drafts
            .mapNotNull { d ->
                draftGroupKey(d.groupId, d.updId, docsById)?.let { it to d.localDraftId }
            }
            .toMap()
        // Ни документа, ни машины — см. IntakeUpdSelectViewModel.
        val emptyDrafts: List<ShipmentStage1DraftEntity> = drafts.filter {
            it.updId == null && it.groupId == null
        }

        // Direction + attachedIds + siteId в одном месте. См. UpdSelectFilter.
        val ownDocs = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = currentSiteId,
            direction = "outbound",
            attachedIds = attachedIds,
        )

        val todayRows = mutableListOf<DispatchUpdRow>()
        val futureRows = mutableListOf<DispatchUpdRow>()
        // Склейка документов одной машины — см. IntakeUpdSelectViewModel.
        for (groupDocs in groupDocsByMachine(ownDocs)) {
            val d = groupDocs.first()
            val draftId = draftsByGroupKey[groupKeyOf(d)]
            val row = DispatchUpdRow(
                document = d,
                // takeIf(isNotBlank) — см. IntakeUpdSelectViewModel.
                supplierName = groupDocs.firstNotNullOfOrNull { doc ->
                    doc.supplierName?.takeIf(String::isNotBlank)
                        ?: doc.supplierId?.let { byCounterpartyId[it]?.name }
                },
                // Без fallback'а по справочнику — см. IntakeUpdSelectViewModel.
                buyerName = groupDocs.firstNotNullOfOrNull {
                    it.buyerName?.takeIf(String::isNotBlank)
                },
                consigneeName = groupDocs.firstNotNullOfOrNull {
                    it.consigneeName?.takeIf(String::isNotBlank)
                },
                draftId = draftId,
                groupId = d.groupId,
                documents = groupDocs,
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
                    consigneeName = null,
                    buyerName = null,
                    draftId = draft.localDraftId,
                ),
            )
        }
        DispatchUpdGroupsState(
            today = groupBySupplier(todayRows),
            future = groupBySupplier(futureRows),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DispatchUpdGroupsState(),
    )

    /** Зеркало `IntakeUpdSelectViewModel.groupBySupplier` — логика та же. */
    private fun groupBySupplier(rows: List<DispatchUpdRow>): List<DispatchUpdGroup> {
        val (manualRows, realRows) = rows.partition { it.document == null }
        val realGroups = groupByParty(realRows) { it.supplierName }
            .map { DispatchUpdGroup(it.key, it.displayName, it.rows) }
        return if (manualRows.isEmpty()) realGroups
            else realGroups + DispatchUpdGroup(MANUAL_PARTY_KEY, MANUAL_GROUP_LABEL, manualRows)
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
                emit(BusinessTime.todayIso())
                delay(60_000L)
            }
        }.distinctUntilChanged()
    }
}
