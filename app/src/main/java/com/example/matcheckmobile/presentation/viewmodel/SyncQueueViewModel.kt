package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SyncQueueViewModel(private val container: AppContainer) : ViewModel() {
    val pendingOperations: StateFlow<List<MaterialOperationEntity>> =
        container.operationRepository.observeUnsynced().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val pendingAttachments: StateFlow<List<OperationAttachmentEntity>> =
        container.attachmentRepository.observePending().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun retryNow() {
        SyncScheduler.requestImmediateSync(container.appContext)
    }
}
