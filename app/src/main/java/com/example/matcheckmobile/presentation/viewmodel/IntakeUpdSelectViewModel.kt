package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
 * `savedReceipts` пока остаются на legacy ReceiptSession (старая схема) —
 * будут переключены позже при перепаре формы приёмки на DeliveryRepository.
 */
data class IntakeUpdRow(
    val document: RemoteSourceDocumentEntity,
    /** Имя поставщика. На сервере приходит в `supplierName` готовое; counterparties используем как fallback. */
    val supplierName: String?,
)

class IntakeUpdSelectViewModel(container: AppContainer) : ViewModel() {

    val rows: StateFlow<List<IntakeUpdRow>> = combine(
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
        docs
            .asSequence()
            .filter { it.id !in attachedIds }
            .map { d ->
                IntakeUpdRow(
                    document = d,
                    supplierName = d.supplierName
                        ?: d.supplierId?.let { byCounterpartyId[it]?.name },
                )
            }
            .toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val savedReceipts: StateFlow<List<SavedReceiptRow>> = combine(
        container.receiptSessionRepository.observeLocalSavedReceipts(),
        container.sourceDocumentRepository.observeAll(),
        container.counterpartyRepository.observeAll(),
        container.database.siteDao().observeAll(),
    ) { sessions, docs, cps, sites ->
        val docsById = docs.associateBy { it.localId }
        val cpsById: Map<String, CounterpartyEntity> = cps.associateBy { it.localId }
        val sitesById: Map<String, SiteEntity> = sites.associateBy { it.localId }
        sessions.map { s ->
            SavedReceiptRow(
                session = s,
                siteName = sitesById[s.siteId]?.name,
                contractorName = s.contractorLocalId?.let { cpsById[it]?.name },
                supplierName = s.supplierLocalId?.let { cpsById[it]?.name },
                updNumber = s.sourceDocumentLocalId?.let { docsById[it]?.docNumber }
                    ?: s.sourceDocumentManualText,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
}
