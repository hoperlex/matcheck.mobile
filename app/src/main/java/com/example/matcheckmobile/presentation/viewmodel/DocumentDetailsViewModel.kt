package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.navigation.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class DocumentDetailsViewModel(
    container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val documentId: String =
        savedStateHandle.get<String>(Routes.ARG_DOCUMENT_ID).orEmpty()

    val document: StateFlow<SourceDocumentEntity?> =
        if (documentId.isEmpty()) flowOf<SourceDocumentEntity?>(null).stateIn(
            viewModelScope, SharingStarted.Eagerly, null,
        ) else container.sourceDocumentRepository.observeById(documentId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    val items: StateFlow<List<SourceDocumentItemEntity>> =
        if (documentId.isEmpty()) flowOf<List<SourceDocumentItemEntity>>(emptyList()).stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList(),
        ) else container.sourceDocumentRepository.observeItems(documentId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
