package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OperationDetailsViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val operationId: String =
        savedStateHandle.get<String>(Routes.ARG_OPERATION_ID).orEmpty()

    val operation: StateFlow<MaterialOperationEntity?> =
        if (operationId.isEmpty()) flowOf<MaterialOperationEntity?>(null).stateIn(
            viewModelScope, SharingStarted.Eagerly, null
        ) else container.operationRepository.observeById(operationId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    val attachments: StateFlow<List<OperationAttachmentEntity>> =
        if (operationId.isEmpty()) flowOf<List<OperationAttachmentEntity>>(emptyList()).stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList()
        ) else container.operationRepository.observeAttachments(operationId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addPhoto(localFilePath: String, type: AttachmentType = AttachmentType.CARGO) {
        if (operationId.isEmpty()) return
        viewModelScope.launch {
            container.operationRepository.attachPhoto(operationId, localFilePath, type)
            SyncScheduler.requestImmediateSync(container.appContext)
        }
    }
}
