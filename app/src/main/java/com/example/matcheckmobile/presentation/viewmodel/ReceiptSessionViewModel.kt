package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.SessionKind
import com.example.matcheckmobile.domain.model.VEHICLE_TYPES
import com.example.matcheckmobile.domain.model.VehicleType
import com.example.matcheckmobile.domain.model.vehicleTypeByCode
import com.example.matcheckmobile.presentation.navigation.Routes
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
    val siteLocalId: String? = null,
    val supplierLocalId: String? = null,
    val contractorLocalId: String? = null,
    val vehicleNumber: String = "",
    val vehicleTypeCode: String? = null,
    val volumeText: String = "",
    val massText: String = "",
    val sourceDocumentLocalId: String? = null,
    val comment: String = "",
    val sessionPhotoPaths: List<String> = emptyList(),
    val confirmedByMol: Boolean = false,
    val headerError: String? = null,
    val isFinalizing: Boolean = false,
    val finalized: Boolean = false,
    val itemForm: ItemFormState = ItemFormState(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiptSessionViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialUpdId: String? = savedStateHandle.get<String>(Routes.ARG_UPD_ID)
    private val initialSessionId: String? = savedStateHandle.get<String>(Routes.ARG_SESSION_ID)

    private val _state = MutableStateFlow(ReceiptSessionUiState())
    val state: StateFlow<ReceiptSessionUiState> = _state.asStateFlow()

    val sites: StateFlow<List<SiteEntity>> =
        container.database.siteDao().observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val suppliers: StateFlow<List<CounterpartyEntity>> =
        container.counterpartyRepository.observeSuppliers().stateIn(
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

    val sessionPhotos: StateFlow<List<OperationAttachmentEntity>> = _state
        .flatMapLatest { ui ->
            val sid = ui.sessionId
            if (sid.isNullOrEmpty()) flowOf(emptyList())
            else container.receiptSessionRepository.observeSessionAttachments(sid)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val currentSite = container.deviceSettings.currentSiteIdFlow.first()
        val existingId = initialSessionId
        if (!existingId.isNullOrEmpty()) {
            val session = container.receiptSessionRepository.findById(existingId)
            if (session != null) {
                _state.update {
                    it.copy(
                        step = ReceiptStep.ITEMS,
                        sessionId = session.localId,
                        siteLocalId = session.siteId,
                        supplierLocalId = session.supplierLocalId,
                        contractorLocalId = session.contractorLocalId,
                        vehicleNumber = session.vehicleNumber,
                        vehicleTypeCode = session.vehicleTypeCode,
                        volumeText = session.volumeM3?.let(::formatDouble).orEmpty(),
                        massText = session.massKg?.let(::formatDouble).orEmpty(),
                        sourceDocumentLocalId = session.sourceDocumentLocalId,
                        comment = session.comment.orEmpty(),
                        confirmedByMol = session.confirmedByMol,
                    )
                }
                return
            }
        }
        _state.update {
            it.copy(
                siteLocalId = if (currentSite.isNotEmpty()) currentSite else null,
                sourceDocumentLocalId = initialUpdId,
            )
        }
    }

    // Header step
    fun setSite(value: String?) = _state.update { it.copy(siteLocalId = value) }
    fun setSupplier(value: String?) = _state.update { it.copy(supplierLocalId = value) }
    fun setContractor(value: String?) = _state.update { it.copy(contractorLocalId = value) }
    fun setVehicleNumber(value: String) = _state.update { it.copy(vehicleNumber = value) }
    fun setSourceDocument(value: String?) = _state.update { it.copy(sourceDocumentLocalId = value) }
    fun setComment(value: String) = _state.update { it.copy(comment = value) }
    fun setVolumeText(value: String) = _state.update { it.copy(volumeText = value) }
    fun setMassText(value: String) = _state.update { it.copy(massText = value) }
    fun setConfirmedByMol(value: Boolean) = _state.update { it.copy(confirmedByMol = value) }
    fun selectVehicleType(type: VehicleType) = _state.update {
        it.copy(
            vehicleTypeCode = type.code,
            volumeText = formatDouble(type.volumeM3),
            massText = formatDouble(type.payloadKg),
        )
    }

    fun addSessionPhoto(path: String) {
        val sid = _state.value.sessionId
        if (sid != null) {
            viewModelScope.launch {
                container.receiptSessionRepository.addSessionPhoto(sid, path)
            }
        } else {
            _state.update { it.copy(sessionPhotoPaths = it.sessionPhotoPaths + path) }
        }
    }

    fun startSession() {
        val current = _state.value
        val vehicle = current.vehicleNumber.trim()
        val siteId = current.siteLocalId
        if (siteId.isNullOrEmpty()) {
            _state.update { it.copy(headerError = "Выберите объект") }
            return
        }
        if (vehicle.isEmpty()) {
            _state.update { it.copy(headerError = "Укажите госномер") }
            return
        }
        viewModelScope.launch {
            val userId = container.deviceSettings.currentUserIdFlow.first().ifEmpty { "user-default" }
            val deviceId = container.deviceSettings.ensureDeviceId()
            val volume = current.volumeText.replace(',', '.').toDoubleOrNull()
            val mass = current.massText.replace(',', '.').toDoubleOrNull()
            val session = container.receiptSessionRepository.createDraft(
                kind = SessionKind.RECEIPT,
                siteId = siteId,
                userId = userId,
                deviceId = deviceId,
                supplierLocalId = current.supplierLocalId,
                contractorLocalId = current.contractorLocalId,
                vehicleNumber = vehicle,
                vehicleTypeCode = current.vehicleTypeCode,
                volumeM3 = volume,
                massKg = mass,
                sourceDocumentLocalId = current.sourceDocumentLocalId,
                comment = current.comment.trim().ifEmpty { null },
            )
            for (path in current.sessionPhotoPaths) {
                container.receiptSessionRepository.addSessionPhoto(session.localId, path)
            }
            _state.update {
                it.copy(
                    step = ReceiptStep.ITEMS,
                    sessionId = session.localId,
                    sessionPhotoPaths = emptyList(),
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

    /** «Сохранить» — приёмка остаётся не закончена (LOCAL_SAVED). */
    fun saveLocally() = persist(complete = false)

    /** «Закончить приёмку» — финал, со статусом COMPLETED. */
    fun completeSession() = persist(complete = true)

    private fun persist(complete: Boolean) {
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
            val current = _state.value
            container.receiptSessionRepository.updateDraftHeader(
                sessionId = sid,
                siteId = current.siteLocalId ?: "site-zilart",
                supplierLocalId = current.supplierLocalId,
                contractorLocalId = current.contractorLocalId,
                vehicleNumber = current.vehicleNumber.trim(),
                vehicleTypeCode = current.vehicleTypeCode,
                volumeM3 = current.volumeText.replace(',', '.').toDoubleOrNull(),
                massKg = current.massText.replace(',', '.').toDoubleOrNull(),
                sourceDocumentLocalId = current.sourceDocumentLocalId,
                comment = current.comment.trim().ifEmpty { null },
                confirmedByMol = current.confirmedByMol,
            )
            if (complete) {
                container.receiptSessionRepository.complete(sid, current.confirmedByMol)
            } else {
                container.receiptSessionRepository.saveLocally(sid)
            }
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

    fun acknowledgeFinalized() = _state.update { ReceiptSessionUiState(finalized = false) }

    // Resolve names for header display
    val resolvedSite: StateFlow<SiteEntity?> = combine(_state, sites) { ui, list ->
        list.firstOrNull { it.localId == ui.siteLocalId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedSupplier: StateFlow<CounterpartyEntity?> = combine(
        _state, suppliers,
    ) { ui, list -> list.firstOrNull { it.localId == ui.supplierLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedContractor: StateFlow<CounterpartyEntity?> = combine(
        _state, contractors,
    ) { ui, list -> list.firstOrNull { it.localId == ui.contractorLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val resolvedDocument: StateFlow<SourceDocumentEntity?> = combine(
        _state, documents,
    ) { ui, list -> list.firstOrNull { it.localId == ui.sourceDocumentLocalId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @Suppress("unused")
    val vehicleTypes: List<VehicleType> = VEHICLE_TYPES

    @Suppress("unused")
    fun resolvedVehicleType(code: String?): VehicleType? = vehicleTypeByCode(code)
}

private fun formatDouble(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
