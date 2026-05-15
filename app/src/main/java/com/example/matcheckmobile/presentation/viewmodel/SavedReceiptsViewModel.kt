package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SavedReceiptsViewModel(container: AppContainer) : ViewModel() {
    val rows: StateFlow<List<SavedReceiptRow>> = combine(
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
