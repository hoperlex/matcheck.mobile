package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.AttachmentType
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OperationFormState(
    val materialName: String = "",
    val quantityText: String = "",
    val unit: String = "шт",
    val vehicleNumber: String = "",
    val comment: String = "",
    val pendingPhotoPaths: List<String> = emptyList(),
    val isSubmitting: Boolean = false,
    val savedOperationId: String? = null,
    val errorMessage: String? = null,
)

abstract class OperationFormViewModel(
    protected val container: AppContainer,
    private val operationType: OperationType,
) : ViewModel() {

    private val _state = MutableStateFlow(OperationFormState())
    val state: StateFlow<OperationFormState> = _state.asStateFlow()

    fun setMaterialName(value: String) = _state.update { it.copy(materialName = value) }
    fun setQuantity(value: String) = _state.update { it.copy(quantityText = value) }
    fun setUnit(value: String) = _state.update { it.copy(unit = value) }
    fun setVehicleNumber(value: String) = _state.update { it.copy(vehicleNumber = value) }
    fun setComment(value: String) = _state.update { it.copy(comment = value) }
    fun addPhotoPath(path: String) = _state.update { it.copy(pendingPhotoPaths = it.pendingPhotoPaths + path) }
    fun removePhotoPath(path: String) = _state.update { it.copy(pendingPhotoPaths = it.pendingPhotoPaths - path) }
    fun clearError() = _state.update { it.copy(errorMessage = null) }
    fun resetSaved() = _state.update { OperationFormState() }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        val name = current.materialName.trim()
        val qty = current.quantityText.replace(',', '.').toDoubleOrNull()
        if (name.isEmpty()) {
            _state.update { it.copy(errorMessage = "Укажите наименование материала") }
            return
        }
        if (qty == null || qty <= 0.0) {
            _state.update { it.copy(errorMessage = "Укажите корректное количество") }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val userId = container.deviceSettings.currentUserIdFlow.first().ifEmpty { "user-default" }
            val siteId = container.deviceSettings.currentSiteIdFlow.first().ifEmpty { "site-default" }
            val deviceId = container.deviceSettings.ensureDeviceId()
            val operation = container.createOperationUseCase(
                type = operationType,
                siteId = siteId,
                materialNameRaw = name,
                quantity = qty,
                unit = current.unit.ifBlank { "шт" },
                userId = userId,
                deviceId = deviceId,
                vehicleNumber = current.vehicleNumber.trim().ifEmpty { null },
                driverName = null,
                comment = current.comment.trim().ifEmpty { null },
            )
            current.pendingPhotoPaths.forEach { path ->
                container.operationRepository.attachPhoto(
                    operationLocalId = operation.localId,
                    localFilePath = path,
                    attachmentType = AttachmentType.CARGO,
                )
            }
            SyncScheduler.requestImmediateSync(container.appContext)
            _state.update { it.copy(isSubmitting = false, savedOperationId = operation.localId) }
        }
    }
}

class ReceiptViewModel(container: AppContainer) :
    OperationFormViewModel(container, OperationType.RECEIPT)

class DispatchViewModel(container: AppContainer) :
    OperationFormViewModel(container, OperationType.DISPATCH)
