package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.SessionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DispatchSessionUiState(
    val siteLocalId: String? = null,
    val contractorLocalId: String? = null,
    val vehicleNumber: String = "",
    val sourceDocumentLocalId: String? = null,
    val comment: String = "",
    val pendingPhotoPaths: List<String> = emptyList(),
    val isSubmitting: Boolean = false,
    val savedSessionId: String? = null,
    val errorMessage: String? = null,
)

class DispatchSessionViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DispatchSessionUiState())
    val state: StateFlow<DispatchSessionUiState> = _state.asStateFlow()

    val sites: StateFlow<List<SiteEntity>> =
        container.database.siteDao().observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val contractors: StateFlow<List<CounterpartyEntity>> =
        container.counterpartyRepository.observeContractors().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val documents: StateFlow<List<SourceDocumentEntity>> =
        container.sourceDocumentRepository.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val resolvedSite: StateFlow<SiteEntity?> = combine(_state, sites) { ui, list ->
        list.firstOrNull { it.localId == ui.siteLocalId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedContractor: StateFlow<CounterpartyEntity?> = combine(
        _state, contractors,
    ) { ui, list -> list.firstOrNull { it.localId == ui.contractorLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedDocument: StateFlow<SourceDocumentEntity?> = combine(
        _state, documents,
    ) { ui, list -> list.firstOrNull { it.localId == ui.sourceDocumentLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val currentSite = container.deviceSettings.currentSiteIdFlow.first()
            if (currentSite.isNotEmpty()) {
                _state.update { it.copy(siteLocalId = currentSite) }
            }
        }
    }

    fun setSite(value: String?) = _state.update { it.copy(siteLocalId = value) }
    fun setContractor(value: String?) = _state.update { it.copy(contractorLocalId = value) }
    fun setVehicleNumber(value: String) = _state.update { it.copy(vehicleNumber = value) }
    fun setSourceDocument(value: String?) = _state.update { it.copy(sourceDocumentLocalId = value) }
    fun setComment(value: String) = _state.update { it.copy(comment = value) }
    fun addPhotoPath(path: String) = _state.update {
        it.copy(pendingPhotoPaths = it.pendingPhotoPaths + path)
    }
    fun resetSaved() = _state.update { DispatchSessionUiState() }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        val site = current.siteLocalId
        if (site.isNullOrEmpty()) {
            _state.update { it.copy(errorMessage = "Выберите объект") }
            return
        }
        if (current.vehicleNumber.trim().isEmpty()) {
            _state.update { it.copy(errorMessage = "Укажите госномер") }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val userId = container.deviceSettings.currentUserIdFlow.first().ifEmpty { "user-default" }
            val deviceId = container.deviceSettings.ensureDeviceId()
            val session = container.receiptSessionRepository.createDraft(
                kind = SessionKind.DISPATCH,
                siteId = site,
                userId = userId,
                deviceId = deviceId,
                supplierLocalId = null,
                contractorLocalId = current.contractorLocalId,
                vehicleNumber = current.vehicleNumber.trim(),
                vehicleTypeCode = null,
                volumeM3 = null,
                massKg = null,
                sourceDocumentLocalId = current.sourceDocumentLocalId,
                sourceDocumentManualText = null,
                comment = current.comment.trim().ifEmpty { null },
            )
            for (path in current.pendingPhotoPaths) {
                container.receiptSessionRepository.addSessionPhoto(session.localId, path)
            }
            container.receiptSessionRepository.saveLocally(session.localId)
            _state.update {
                it.copy(isSubmitting = false, savedSessionId = session.localId)
            }
        }
    }
}
