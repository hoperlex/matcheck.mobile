package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainStatusUiState(
    val pendingOperations: Int = 0,
    val pendingSessions: Int = 0,
    val pendingAttachments: Int = 0,
) {
    val totalPending: Int get() = pendingOperations + pendingSessions + pendingAttachments
}

class MainStatusViewModel(container: AppContainer) : ViewModel() {
    val status: StateFlow<MainStatusUiState> = combine(
        container.operationRepository.observeUnsyncedCount(),
        container.database.receiptSessionDao()
            .observeCountBySyncStatuses(OperationRepository.UNSYNCED),
        container.attachmentRepository.observePendingCount(),
    ) { ops, sessions, atts -> MainStatusUiState(ops, sessions, atts) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainStatusUiState(),
        )
}
