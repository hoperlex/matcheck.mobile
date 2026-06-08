package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.ShipmentStage1DraftState
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Зеркало [Stage1FormViewModel] для отгрузки. Создаёт Shipment со
 * статусом `shipped` (аналог `filled` у delivery) через [ShipmentRepository.upsert].
 * `kind='contractor'` по умолчанию (наиболее частый сценарий); receiver
 * подтягивается из УПД: recipientMolId приоритетнее recipientId.
 *
 * UI идентичен Stage1FormScreen — отличается только заголовок «Новая отгрузка».
 */
@OptIn(FlowPreview::class)
class DispatchStage1FormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val updId: String? = savedStateHandle[Routes.ARG_UPD_ID]
    private val initialDraftId: String? = savedStateHandle[Routes.ARG_DRAFT_ID]
    private val draftLocalId: String = initialDraftId ?: UUID.randomUUID().toString()

    private val _state = MutableStateFlow(DispatchStage1FormUiState(updId = updId))
    val state: StateFlow<DispatchStage1FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restoreDraftOrInitDefaults() }
        viewModelScope.launch { resolveSiteName() }
        viewModelScope.launch { observeAutoSave() }
    }

    private suspend fun restoreDraftOrInitDefaults() {
        val restored = when {
            initialDraftId != null -> container.shipmentStage1DraftRepository.findById(initialDraftId)
            !updId.isNullOrBlank() -> container.shipmentStage1DraftRepository.findByUpdId(updId)
            else -> null
        }?.let { container.shipmentStage1DraftRepository.toState(it) }

        if (restored != null) {
            _state.update {
                it.copy(
                    updId = restored.updId,
                    documentPhotoPaths = restored.documentPhotoPaths,
                    cargoPhotoPaths = restored.cargoPhotoPaths,
                    vehicleTypeCode = restored.vehicleTypeCode,
                    materials = restored.materials,
                    commentText = restored.commentText,
                    licensePlate = restored.licensePlate,
                    manualUpdText = restored.manualUpdText,
                    shipmentPurpose = restored.shipmentPurpose,
                    inTransit = restored.inTransit,
                )
            }
        }

        val id = updId
        if (!id.isNullOrBlank()) {
            preloadFromSourceDocument(id, fillMaterials = restored == null)
        }
    }

    private suspend fun preloadFromSourceDocument(id: String, fillMaterials: Boolean) {
        val items = runCatching {
            container.database.remoteSourceDocumentDao().findItemsBySource(id)
        }.getOrDefault(emptyList())
        val docMeta = runCatching {
            container.database.remoteSourceDocumentDao().findById(id)
        }.getOrNull()

        if (docMeta != null) {
            _state.update {
                it.copy(
                    updSupplierId = docMeta.supplierId,
                    // Для отгрузки получателем чаще выступает recipientId УПД
                    // (контрагент-получатель) либо МОЛ-получатель. Если МОЛ
                    // указан — он перебивает контрагента (XOR на сервере).
                    updReceiverCounterpartyId = docMeta.recipientId,
                    updReceiverMolId = docMeta.recipientMolId,
                )
            }
        }

        if (items.isEmpty()) return

        val financials = items.map {
            UpdItemFinancials(
                lineNo = it.lineNo,
                price = it.price,
                vatRate = it.vatRate,
                vatSum = it.vatSum,
            )
        }
        val drafts = items.map {
            MaterialDraft(name = it.nameRaw, qty = it.qty, unit = it.unit)
        }
        var volume = 0.0
        var massKg = 0.0
        var hasVolume = false
        var hasMass = false
        for (item in items) {
            val qty = item.qty.toDoubleOrNull() ?: 0.0
            item.volumeM3?.toDoubleOrNull()?.let { volume += it * qty; hasVolume = true }
            item.massKg?.toDoubleOrNull()?.let { massKg += it * qty; hasMass = true }
        }
        val gauge = if (hasVolume || hasMass) VehicleLoadInfo(
            totalVolumeM3 = if (hasVolume) volume else null,
            totalMassT = if (hasMass) massKg / 1000.0 else null,
        ) else null
        _state.update { current ->
            val withMaterials = if (fillMaterials && current.materials.isEmpty())
                current.copy(materials = drafts)
            else current
            withMaterials.copy(loadInfo = gauge, materialFinancials = financials)
        }
    }

    private suspend fun observeAutoSave() {
        _state
            .drop(1)
            .debounce(300L)
            .distinctUntilChanged { a, b -> a.draftPayloadEquals(b) }
            .collect { snapshot ->
                if (snapshot.finalized) return@collect
                val hasAnyPhoto = snapshot.documentPhotoPaths.isNotEmpty() ||
                    snapshot.cargoPhotoPaths.isNotEmpty()
                if (hasAnyPhoto) {
                    container.shipmentStage1DraftRepository.upsert(snapshot.toDraftState())
                } else {
                    container.shipmentStage1DraftRepository.deleteById(draftLocalId)
                }
            }
    }

    private fun DispatchStage1FormUiState.toDraftState(): ShipmentStage1DraftState {
        val now = System.currentTimeMillis()
        return ShipmentStage1DraftState(
            localDraftId = draftLocalId,
            updId = updId,
            documentPhotoPaths = documentPhotoPaths,
            cargoPhotoPaths = cargoPhotoPaths,
            vehicleTypeCode = vehicleTypeCode,
            materials = materials,
            commentText = commentText,
            licensePlate = licensePlate,
            manualUpdText = manualUpdText,
            shipmentPurpose = shipmentPurpose,
            inTransit = inTransit,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun resolveSiteName() {
        val fromUpd = updId?.let { id ->
            runCatching {
                container.database.remoteSourceDocumentDao().findById(id)?.siteName
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }

        val resolved = fromUpd ?: run {
            val siteId = container.tokenStorage.state.value.siteId ?: return@run null
            runCatching {
                container.database.remoteSiteDao().findById(siteId)?.name
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (resolved != null) _state.update { it.copy(siteName = resolved) }
    }

    fun onDocumentPhotoTaken(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths + path) }
    }

    fun removeDocumentPhoto(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths - path) }
    }

    fun onCargoPhotoTaken(path: String) {
        viewModelScope.launch {
            stampWatermark(path)
            _state.update { it.copy(cargoPhotoPaths = it.cargoPhotoPaths + path) }
        }
    }

    fun removeCargoPhoto(path: String) {
        _state.update { it.copy(cargoPhotoPaths = it.cargoPhotoPaths - path) }
    }

    private suspend fun stampWatermark(path: String) {
        runCatching {
            val coords = container.locationProvider.fetchCurrent()
            val siteName = _state.value.siteName
            withContext(Dispatchers.IO) {
                container.metadataWatermark.applyTo(File(path), coords, siteName)
            }
        }
    }

    fun selectVehicle(code: String?) { _state.update { it.copy(vehicleTypeCode = code) } }
    fun setMaterials(drafts: List<MaterialDraft>) { _state.update { it.copy(materials = drafts) } }
    fun setComment(text: String) { _state.update { it.copy(commentText = text) } }
    fun setLicensePlate(text: String) {
        _state.update { it.copy(licensePlate = text, showPlateError = false) }
    }
    fun setManualUpd(text: String) { _state.update { it.copy(manualUpdText = text) } }
    /** Выбор «Тип отгрузки» из выпадающего списка на empty-draft форме. */
    fun setShipmentPurpose(value: String?) { _state.update { it.copy(shipmentPurpose = value) } }
    /** Чекбокс «Транзит» — см. DispatchStage1FormUiState.inTransit. */
    fun setInTransit(value: Boolean) { _state.update { it.copy(inTransit = value) } }
    fun dismissError() { _state.update { it.copy(error = null) } }

    fun finalizeStage1() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized) return

        val plate = cur.licensePlate.trim()
        if (plate.isBlank()) {
            _state.update { it.copy(showPlateError = true, error = "Введите госномер") }
            return
        }
        // Regex-валидация формата убрана: те же резоны что в Stage1FormViewModel
        // (спецтехника / прицепы / ведомственные номера). Достаточно непустоты.
        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        // Pre-flight: сервер validateKindLinks для kind='contractor' требует
        // получателя ТОЛЬКО для picked-UPD случая (когда привязан документ).
        // Empty-draft (updId == null, «Создать отгрузку» без УПД) проходит
        // без получателя — менеджер на портале может дозaпoлнить позже.
        // См. server shipments.ts validateKindLinks: empty-draft пропускает
        // проверку receiver_required.
        if (cur.updId != null) {
            val hasReceiver =
                !cur.updReceiverCounterpartyId.isNullOrBlank() ||
                    !cur.updReceiverMolId.isNullOrBlank()
            if (!hasReceiver) {
                _state.update {
                    it.copy(
                        error = "У накладной не указан получатель. " +
                            "Попросите менеджера дозаполнить документ в портале.",
                    )
                }
                return
            }
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val financialsByLine = cur.materialFinancials.associateBy { it.lineNo }
                val items = cur.materials
                    .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
                    .mapIndexed { idx, m ->
                        val lineNo = idx + 1
                        val fin = financialsByLine[lineNo]
                        ShipmentRepository.ItemInput(
                            nameRaw = m.name.trim().ifEmpty { "—" },
                            qtyActual = m.qty.trim().ifEmpty { null },
                            unit = m.unit.ifEmpty { "шт" },
                            lineNo = lineNo,
                            price = fin?.price,
                            vatRate = fin?.vatRate,
                            vatSum = fin?.vatSum,
                        )
                    }

                val sourceDocIds = cur.updId?.let { listOf(it) } ?: emptyList()
                val userComment = cur.commentText.trim()
                val manualUpd = cur.manualUpdText.trim()
                // «Тип отгрузки» — теперь идёт отдельным полем в UpsertInput.purpose
                // (сервер: миграция 0049, поле shipments.purpose). В comment больше
                // не пихаем, чтобы на веб-портале можно было показать чипом
                // и фильтровать. Только для empty-draft (updId == null) — для
                // отгрузок с УПД тип определяется самим документом.
                val finalPurpose = cur.shipmentPurpose?.takeIf { it.isNotBlank() && cur.updId == null }
                val commentParts = buildList {
                    if (userComment.isNotEmpty()) add("1 Этап: \"$userComment\"")
                    if (manualUpd.isNotEmpty() && cur.updId == null) add("Примечание: $manualUpd")
                }
                val commentForServer = commentParts.joinToString("\n").ifEmpty { null }

                // XOR receiver на сервере: либо receiverCounterpartyId, либо
                // receiverMolId. Приоритет МОЛ.
                val finalReceiverMolId = cur.updReceiverMolId
                val finalReceiverCpId = if (finalReceiverMolId == null) cur.updReceiverCounterpartyId else null

                val shipmentId = container.shipmentRepository.upsert(
                    ShipmentRepository.UpsertInput(
                        statusCode = "shipped",
                        kind = "contractor",
                        siteId = siteId,
                        receiverCounterpartyId = finalReceiverCpId,
                        receiverMolId = finalReceiverMolId,
                        vehiclePlate = plate.ifEmpty { null },
                        shippedAt = java.time.Instant.now().toString(),
                        comment = commentForServer,
                        purpose = finalPurpose,
                        inTransit = cur.inTransit,
                        sourceDocumentIds = sourceDocIds,
                        items = items,
                    ),
                )

                // Зеркало Stage1FormViewModel — сохраняем выбранный тип
                // транспорта в локальной таблице shipment_local_meta, чтобы
                // на 2 Этапе восстановить выбор (в shipment DTO такого поля
                // нет, иначе терялось бы при /sync).
                container.shipmentRepository.setVehicleType(shipmentId, cur.vehicleTypeCode)

                // Все фото 1-го этапа отгрузки помечаем stage='before' (зеркало
                // приёмки). Stage2 будет ставить 'after' — тогда веб-портал
                // разделит «1 Этап (N) / 2 Этап (M)» в шапке отгрузки.
                val photoErrors = mutableListOf<String>()
                cur.documentPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForShipment(
                            shipmentId = shipmentId,
                            kind = "document",
                            sourceUri = uri,
                            stage = "before",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("DispatchStage1", "doc photo failed: $path", t)
                        photoErrors += "док ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }
                cur.cargoPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForShipment(
                            shipmentId = shipmentId,
                            kind = "cargo",
                            sourceUri = uri,
                            stage = "before",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("DispatchStage1", "cargo photo failed: $path", t)
                        photoErrors += "груз ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }

                if (photoErrors.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            error = "Не сохранились фото: ${photoErrors.joinToString("; ")}",
                        )
                    }
                    return@launch
                }

                MatcheckSyncScheduler.requestImmediateSync(container.appContext)
                container.shipmentStage1DraftRepository.deleteById(draftLocalId)
                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }
}

data class DispatchStage1FormUiState(
    val updId: String? = null,
    val siteName: String? = null,
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    val materialFinancials: List<UpdItemFinancials> = emptyList(),
    val updSupplierId: String? = null,
    val updReceiverCounterpartyId: String? = null,
    val updReceiverMolId: String? = null,
    val commentText: String = "",
    val licensePlate: String = "",
    val showPlateError: Boolean = false,
    val manualUpdText: String = "",
    /**
     * Выбор «Тип отгрузки» в выпадающем списке на форме «Новая отгрузка»
     * (видна только при `updId == null`). Допустимые значения см.
     * [SHIPMENT_PURPOSE_OPTIONS]. На finalize добавляется префиксом
     * «Тип: ...» в comment, чтобы видеть на веб-портале.
     */
    val shipmentPurpose: String? = null,
    /**
     * Транзит — чекбокс инспектора на 1 этапе. Default false.
     * Сохраняется в shipment_stage1_draft, отправляется в
     * ShipmentRepository.upsert. На веб-портале — тег «🚚 Транзит» в
     * шапке карточки отгрузки.
     */
    val inTransit: Boolean = false,
    val loadInfo: VehicleLoadInfo? = null,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

private fun DispatchStage1FormUiState.draftPayloadEquals(other: DispatchStage1FormUiState): Boolean =
    updId == other.updId &&
        documentPhotoPaths == other.documentPhotoPaths &&
        cargoPhotoPaths == other.cargoPhotoPaths &&
        vehicleTypeCode == other.vehicleTypeCode &&
        materials == other.materials &&
        commentText == other.commentText &&
        licensePlate == other.licensePlate &&
        manualUpdText == other.manualUpdText &&
        shipmentPurpose == other.shipmentPurpose &&
        inTransit == other.inTransit

/**
 * Допустимые значения dropdown «Тип отгрузки» на форме «Новая отгрузка»
 * (empty-draft, updId=null). При finalize добавляется префиксом «Тип: …»
 * в comment, чтобы видеть на веб-портале.
 */
val SHIPMENT_PURPOSE_OPTIONS = listOf(
    "Вывоз материала",
    "Перемещение на объект",
    "Вывоз мусора",
    "Другое",
)
