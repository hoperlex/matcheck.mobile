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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReceiptStep { MAIN, ITEM_FORM }

data class ItemFormState(
    val materialName: String = "",
    val quantityText: String = "",
    val unit: String = "шт",
    val comment: String = "",
    val photoPaths: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class ReceiptSessionUiState(
    val step: ReceiptStep = ReceiptStep.MAIN,
    val sessionId: String? = null,
    val siteLocalId: String? = null,
    val supplierLocalId: String? = null,
    val contractorLocalId: String? = null,
    val vehicleNumber: String = "",
    val vehicleTypeCode: String? = null,
    val volumeText: String = "",
    val massText: String = "",
    val sourceDocumentLocalId: String? = null,
    val sourceDocumentText: String = "",
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

    private val sessionIdFlow: StateFlow<String?> = _state
        .map { it.sessionId }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val items: StateFlow<List<MaterialOperationEntity>> = sessionIdFlow
        .flatMapLatest { sid ->
            if (sid.isNullOrEmpty()) flowOf(emptyList())
            else container.receiptSessionRepository.observeItems(sid)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val sessionPhotos: StateFlow<List<OperationAttachmentEntity>> = sessionIdFlow
        .flatMapLatest { sid ->
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
                val docText = session.sourceDocumentManualText
                    ?: session.sourceDocumentLocalId?.let {
                        container.sourceDocumentRepository.findById(it)?.docNumber?.let { n -> "УПД $n" }
                    }
                    ?: ""
                _state.update {
                    it.copy(
                        sessionId = session.localId,
                        siteLocalId = session.siteId,
                        supplierLocalId = session.supplierLocalId,
                        contractorLocalId = session.contractorLocalId,
                        vehicleNumber = session.vehicleNumber,
                        vehicleTypeCode = session.vehicleTypeCode,
                        volumeText = session.volumeM3?.let(::formatDouble).orEmpty(),
                        massText = session.massKg?.let(::formatDouble).orEmpty(),
                        sourceDocumentLocalId = session.sourceDocumentLocalId,
                        sourceDocumentText = docText,
                        comment = session.comment.orEmpty(),
                        confirmedByMol = session.confirmedByMol,
                    )
                }
                return
            }
        }
        val initialDocText = initialUpdId?.let {
            container.sourceDocumentRepository.findById(it)?.docNumber?.let { n -> "УПД $n" }
        }.orEmpty()
        _state.update {
            it.copy(
                siteLocalId = if (currentSite.isNotEmpty()) currentSite else null,
                sourceDocumentLocalId = initialUpdId,
                sourceDocumentText = initialDocText,
            )
        }
    }

    // Field setters
    fun setSite(value: String?) = _state.update { it.copy(siteLocalId = value) }
    fun setSupplier(value: String?) = _state.update { it.copy(supplierLocalId = value) }
    fun setContractor(value: String?) = _state.update { it.copy(contractorLocalId = value) }
    fun setVehicleNumber(value: String) = _state.update { it.copy(vehicleNumber = value) }
    /** Выбор УПД из списка: запоминаем id и подставляем номер в текстовое поле. */
    fun setSourceDocument(value: String?) {
        if (value == null) {
            _state.update { it.copy(sourceDocumentLocalId = null, sourceDocumentText = "") }
            return
        }
        viewModelScope.launch {
            val doc = container.sourceDocumentRepository.findById(value)
            _state.update {
                it.copy(
                    sourceDocumentLocalId = value,
                    sourceDocumentText = doc?.docNumber?.let { n -> "УПД $n" } ?: it.sourceDocumentText,
                )
            }
        }
    }

    /** Ручной ввод: текст пишем как есть, привязку к существующему УПД сбрасываем. */
    fun setSourceDocumentText(value: String) = _state.update {
        it.copy(sourceDocumentText = value, sourceDocumentLocalId = null)
    }
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

    /** Удалить уже сохранённое в БД сессионное фото. */
    fun removeSessionPhoto(attachmentId: String) {
        viewModelScope.launch {
            container.receiptSessionRepository.removeSessionPhoto(attachmentId)
        }
    }

    /** Удалить in-memory фото (приёмка ещё не создана как сессия). */
    fun removeInMemorySessionPhoto(path: String) {
        _state.update {
            it.copy(sessionPhotoPaths = it.sessionPhotoPaths.filterNot { p -> p == path })
        }
        runCatching { java.io.File(path).delete() }
    }

    /** Удалить фото-черновик у позиции (item-form всегда in-memory до сохранения позиции). */
    fun removeItemPhoto(path: String) {
        _state.update {
            it.copy(itemForm = it.itemForm.copy(photoPaths = it.itemForm.photoPaths - path))
        }
        runCatching { java.io.File(path).delete() }
    }

    // Item form
    fun openItemForm() {
        viewModelScope.launch {
            val sid = ensureSession() ?: return@launch
            _state.update {
                it.copy(
                    step = ReceiptStep.ITEM_FORM,
                    sessionId = sid,
                    itemForm = ItemFormState(),
                )
            }
        }
    }

    fun closeItemForm() = _state.update { it.copy(step = ReceiptStep.MAIN, itemForm = ItemFormState()) }
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
            _state.update { it.copy(step = ReceiptStep.MAIN, itemForm = ItemFormState()) }
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
        if (_state.value.isFinalizing) return
        val current = _state.value
        val siteId = current.siteLocalId
        val vehicle = current.vehicleNumber.trim()
        if (siteId.isNullOrEmpty()) {
            _state.update { it.copy(headerError = "Выберите объект") }
            return
        }
        if (vehicle.isEmpty()) {
            _state.update { it.copy(headerError = "Укажите госномер") }
            return
        }
        _state.update { it.copy(isFinalizing = true, headerError = null) }
        viewModelScope.launch {
            val sid = ensureSession() ?: run {
                _state.update { it.copy(isFinalizing = false) }
                return@launch
            }
            container.receiptSessionRepository.updateDraftHeader(
                sessionId = sid,
                siteId = siteId,
                supplierLocalId = current.supplierLocalId,
                contractorLocalId = current.contractorLocalId,
                vehicleNumber = vehicle,
                vehicleTypeCode = current.vehicleTypeCode,
                volumeM3 = current.volumeText.replace(',', '.').toDoubleOrNull(),
                massKg = current.massText.replace(',', '.').toDoubleOrNull(),
                sourceDocumentLocalId = current.sourceDocumentLocalId,
                sourceDocumentManualText = current.manualDocText(),
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

    /**
     * Возвращает sessionId. Если черновика ещё нет — создаёт его из текущих полей.
     * Перенесёт накопленные «до создания» фото из state на саму сессию.
     * Если объект не выбран — вернёт null и выставит headerError.
     */
    private suspend fun ensureSession(): String? {
        val current = _state.value
        current.sessionId?.let { return it }
        val siteId = current.siteLocalId
        if (siteId.isNullOrEmpty()) {
            _state.update { it.copy(headerError = "Выберите объект") }
            return null
        }
        val userId = container.deviceSettings.currentUserIdFlow.first().ifEmpty { "user-default" }
        val deviceId = container.deviceSettings.ensureDeviceId()
        val session = container.receiptSessionRepository.createDraft(
            kind = SessionKind.RECEIPT,
            siteId = siteId,
            userId = userId,
            deviceId = deviceId,
            supplierLocalId = current.supplierLocalId,
            contractorLocalId = current.contractorLocalId,
            vehicleNumber = current.vehicleNumber.trim(),
            vehicleTypeCode = current.vehicleTypeCode,
            volumeM3 = current.volumeText.replace(',', '.').toDoubleOrNull(),
            massKg = current.massText.replace(',', '.').toDoubleOrNull(),
            sourceDocumentLocalId = current.sourceDocumentLocalId,
            sourceDocumentManualText = current.manualDocText(),
            comment = current.comment.trim().ifEmpty { null },
        )
        for (path in current.sessionPhotoPaths) {
            container.receiptSessionRepository.addSessionPhoto(session.localId, path)
        }
        _state.update {
            it.copy(sessionId = session.localId, sessionPhotoPaths = emptyList())
        }
        return session.localId
    }

    fun cancelDraft() {
        val sid = _state.value.sessionId ?: run {
            _state.update { ReceiptSessionUiState(finalized = true) }
            return
        }
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

/**
 * Если УПД выбран из списка — ручной текст не нужен (отобразим номер из БД).
 * Иначе сохраняем то, что юзер ввёл вручную (пустоту приводим к null).
 */
private fun ReceiptSessionUiState.manualDocText(): String? =
    if (sourceDocumentLocalId != null) null
    else sourceDocumentText.trim().ifEmpty { null }
