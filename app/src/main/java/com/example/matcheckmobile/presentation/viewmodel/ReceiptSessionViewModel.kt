package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReceiptStep { HEADER, ITEMS, ITEM_FORM }

data class ItemFormState(
    val materialName: String = "",
    val quantityText: String = "",
    val unit: String = "шт",
    val comment: String = "",
    val photoPaths: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class ReceiptSessionUiState(
    val step: ReceiptStep = ReceiptStep.HEADER,
    val sessionId: String? = null,
    val vehicleNumber: String = "",
    val supplierLocalId: String? = null,
    val sourceDocumentLocalId: String? = null,
    val comment: String = "",
    val headerError: String? = null,
    val isFinalizing: Boolean = false,
    val finalized: Boolean = false,
    val itemForm: ItemFormState = ItemFormState(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiptSessionViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptSessionUiState())
    val state: StateFlow<ReceiptSessionUiState> = _state.asStateFlow()

    val suppliers: StateFlow<List<CounterpartyEntity>> =
        container.counterpartyRepository.observeSuppliers().stateIn(
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

    val items: StateFlow<List<MaterialOperationEntity>> = _state
        .flatMapLatest { ui ->
            val sid = ui.sessionId
            if (sid.isNullOrEmpty()) flowOf(emptyList())
            else container.receiptSessionRepository.observeItems(sid)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // Header step
    fun setVehicleNumber(value: String) = _state.update { it.copy(vehicleNumber = value) }
    fun setSupplier(value: String?) = _state.update { it.copy(supplierLocalId = value) }
    fun setSourceDocument(value: String?) = _state.update { it.copy(sourceDocumentLocalId = value) }
    fun setComment(value: String) = _state.update { it.copy(comment = value) }

    fun startSession() {
        val current = _state.value
        val vehicle = current.vehicleNumber.trim()
        if (vehicle.isEmpty()) {
            _state.update { it.copy(headerError = "Укажите госномер") }
            return
        }
        viewModelScope.launch {
            val userId = container.deviceSettings.currentUserIdFlow.first().ifEmpty { "user-default" }
            val siteId = container.deviceSettings.currentSiteIdFlow.first().ifEmpty { "site-default" }
            val deviceId = container.deviceSettings.ensureDeviceId()
            val session = container.receiptSessionRepository.createDraft(
                siteId = siteId,
                userId = userId,
                deviceId = deviceId,
                supplierLocalId = current.supplierLocalId,
                vehicleNumber = vehicle,
                sourceDocumentLocalId = current.sourceDocumentLocalId,
                comment = current.comment.trim().ifEmpty { null },
            )
            _state.update {
                it.copy(
                    step = ReceiptStep.ITEMS,
                    sessionId = session.localId,
                    headerError = null,
                )
            }
        }
    }

    fun backToHeader() = _state.update { it.copy(step = ReceiptStep.HEADER) }

    // Item form
    fun openItemForm() = _state.update { it.copy(step = ReceiptStep.ITEM_FORM, itemForm = ItemFormState()) }
    fun closeItemForm() = _state.update { it.copy(step = ReceiptStep.ITEMS, itemForm = ItemFormState()) }
    fun setItemMaterialName(v: String) = _state.update { it.copy(itemForm = it.itemForm.copy(materialName = v)) }
    fun setItemQuantity(v: String) = _state.update { it.copy(itemForm = it.itemForm.copy(quantityText = v)) }
    fun setItemUnit(v: String) = _state.update { it.copy(itemForm = it.itemForm.copy(unit = v)) }
    fun setItemComment(v: String) = _state.update { it.copy(itemForm = it.itemForm.copy(comment = v)) }
    fun addItemPhoto(path: String) = _state.update {
        it.copy(itemForm = it.itemForm.copy(photoPaths = it.itemForm.photoPaths + path))
    }

    fun saveItem() {
        val current = _state.value
        val sid = current.sessionId ?: return
        val name = current.itemForm.materialName.trim()
        val qty = current.itemForm.quantityText.replace(',', '.').toDoubleOrNull()
        if (name.isEmpty()) {
            _state.update {
                it.copy(itemForm = it.itemForm.copy(errorMessage = "Укажите наименование"))
            }
            return
        }
        if (qty == null || qty <= 0.0) {
            _state.update {
                it.copy(itemForm = it.itemForm.copy(errorMessage = "Укажите корректное количество"))
            }
            return
        }
        viewModelScope.launch {
            container.receiptSessionRepository.addItem(
                sessionId = sid,
                materialId = null,
                materialNameRaw = name,
                quantity = qty,
                unit = current.itemForm.unit.ifBlank { "шт" },
                comment = current.itemForm.comment.trim().ifEmpty { null },
                photoPaths = current.itemForm.photoPaths,
            )
            _state.update { it.copy(step = ReceiptStep.ITEMS, itemForm = ItemFormState()) }
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            container.receiptSessionRepository.removeItem(itemId)
        }
    }

    fun finalizeSession() {
        val sid = _state.value.sessionId ?: return
        if (_state.value.isFinalizing) return
        _state.update { it.copy(isFinalizing = true) }
        viewModelScope.launch {
            val itemsList = container.receiptSessionRepository.findItems(sid)
            if (itemsList.isEmpty()) {
                _state.update {
                    it.copy(isFinalizing = false, headerError = "Добавьте хотя бы одну позицию")
                }
                return@launch
            }
            container.receiptSessionRepository.finalize(sid)
            SyncScheduler.requestImmediateSync(container.appContext)
            _state.update { it.copy(isFinalizing = false, finalized = true) }
        }
    }

    fun cancelDraft() {
        val sid = _state.value.sessionId ?: return
        viewModelScope.launch {
            container.receiptSessionRepository.cancelDraft(sid)
            _state.update { ReceiptSessionUiState(finalized = true) }
        }
    }

    fun acknowledgeFinalized() = _state.update { ReceiptSessionUiState() }

    // Resolve names for header display
    val resolvedSupplier: StateFlow<CounterpartyEntity?> = combine(
        _state, suppliers,
    ) { ui, list -> list.firstOrNull { it.localId == ui.supplierLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedDocument: StateFlow<SourceDocumentEntity?> = combine(
        _state, documents,
    ) { ui, list -> list.firstOrNull { it.localId == ui.sourceDocumentLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
