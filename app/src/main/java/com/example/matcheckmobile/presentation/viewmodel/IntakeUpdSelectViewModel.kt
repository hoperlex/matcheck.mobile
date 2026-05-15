package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class IntakeUpdRow(
    val document: SourceDocumentEntity,
    val supplierName: String?,
)

class IntakeUpdSelectViewModel(container: AppContainer) : ViewModel() {
    val rows: StateFlow<List<IntakeUpdRow>> = combine(
        container.sourceDocumentRepository.observeUpdWithoutCompletedReceipt(),
        container.counterpartyRepository.observeAll(),
    ) { docs, cps ->
        val byId: Map<String, CounterpartyEntity> = cps.associateBy { it.localId }
        docs.map { d -> IntakeUpdRow(d, d.supplierId?.let { byId[it]?.name }) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
}
