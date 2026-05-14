package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainStatusUiState(
    val pendingOperations: Int = 0,
    val pendingAttachments: Int = 0,
)

class MainStatusViewModel(container: AppContainer) : ViewModel() {
    val status: StateFlow<MainStatusUiState> = combine(
        container.operationRepository.observeUnsyncedCount(),
        container.attachmentRepository.observePendingCount(),
    ) { ops, atts -> MainStatusUiState(ops, atts) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainStatusUiState(),
        )
}
