package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DocumentRow(
    val document: SourceDocumentEntity,
    val supplierName: String?,
)

data class SavedReceiptRow(
    val session: ReceiptSessionEntity,
    val siteName: String?,
    val contractorName: String?,
    val supplierName: String?,
    val updNumber: String?,
)

class DocumentsViewModel(container: AppContainer) : ViewModel() {

    private val counterparties = container.counterpartyRepository.observeAll()
    private val documents = container.sourceDocumentRepository.observeAll()
    private val sites = container.database.siteDao().observeAll()
    private val savedReceipts = container.receiptSessionRepository.observeLocalSavedReceipts()

    val rows: StateFlow<List<DocumentRow>> = combine(
        documents,
        counterparties,
    ) { docs, cps ->
        val byId: Map<String, CounterpartyEntity> = cps.associateBy { it.localId }
        docs.map { d -> DocumentRow(d, d.supplierId?.let { byId[it]?.name }) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val savedReceiptRows: StateFlow<List<SavedReceiptRow>> = combine(
        savedReceipts,
        documents,
        counterparties,
        sites,
    ) { sessions, docs, cps, siteList ->
        val docsById = docs.associateBy { it.localId }
        val cpsById: Map<String, CounterpartyEntity> = cps.associateBy { it.localId }
        val sitesById: Map<String, SiteEntity> = siteList.associateBy { it.localId }
        sessions.map { s ->
            SavedReceiptRow(
                session = s,
                siteName = sitesById[s.siteId]?.name,
                contractorName = s.contractorLocalId?.let { cpsById[it]?.name },
                supplierName = s.supplierLocalId?.let { cpsById[it]?.name },
                updNumber = s.sourceDocumentLocalId?.let { docsById[it]?.docNumber },
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
}
