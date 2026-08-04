package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.ShipmentStage2DraftState
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.Stage1PhotoItem
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

/**
 * Зеркало [Stage2FormViewModel] для отгрузки. Грузит Shipment по
 * [Routes.ARG_SHIPMENT_ID], переводит статус с `shipped` на `confirmed_mol`
 * на финализации, ведёт черновик правок в [ShipmentStage2DraftRepository].
 */
@OptIn(FlowPreview::class)
class DispatchStage2FormViewModel(
    private val container: AppContainer,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val shipmentId: String =
        savedStateHandle[Routes.ARG_SHIPMENT_ID]
            ?: error("DispatchStage2FormViewModel: shipmentId required in route")

    private val _state = MutableStateFlow(DispatchStage2FormUiState(shipmentId = shipmentId))
    val state: StateFlow<DispatchStage2FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            loadInitial()
            restoreDraftIfAny()
            // Если инспектор уже редактировал «Примечание» (он же manual-УПД) на
            // этом 2 Этапе — savedStateHandle хранит его правку. Применяем после
            // loadInitial/restoreDraft, чтобы override server-snapshot значение.
            // Используем SavedStateHandle (а не Room-draft), чтобы не делать
            // миграцию схемы: правка чисто UI-state, переживёт Activity
            // recreation + process death через onSaveInstanceState.
            restoreInheritedNoteFromSavedStateIfAny()
            // См. Stage2FormViewModel.backfillStage1PhotosFromServer.
            backfillStage1PhotosFromServer()
            observeAutoSave()
        }
    }

    private fun restoreInheritedNoteFromSavedStateIfAny() {
        if (!savedStateHandle.contains(KEY_INHERITED_NOTE)) return
        val saved = savedStateHandle.get<String?>(KEY_INHERITED_NOTE)
        _state.update { it.copy(inheritedNote = saved?.takeIf { v -> v.isNotEmpty() }) }
    }

    /**
     * Зеркало [com.example.matcheckmobile.presentation.viewmodel.Stage2FormViewModel.backfillStage1PhotosFromServer]
     * для отгрузки. GET /api/v1/shipments/{id} → upsert photos в Room → обновляем UiState.
     */
    private suspend fun backfillStage1PhotosFromServer() {
        val dto = runCatching { container.shipmentsApi.get(shipmentId) }.getOrNull() ?: return
        val dao = container.database.remoteShipmentDao()
        dto.photos.forEach { p ->
            with(RemoteMappers) { dao.upsertPhoto(p.toEntity(shipmentId)) }
        }
        val stage1RawPhotos = dao.findPhotosByShipment(shipmentId).filter { it.stage == "before" }
        val docs = stage1RawPhotos.filter { it.kind == "document" }
            .map { it.toStage1Item(captionLabel = "Документ") }
        val others = stage1RawPhotos.filterNot { it.kind == "document" }
            .map { it.toStage1Item(captionLabel = "Груз/машина") }
        _state.update {
            it.copy(stage1DocumentPhotos = docs, stage1VehiclePhotos = others)
        }
    }

    private suspend fun loadInitial() {
        val shipment = container.shipmentRepository.findById(shipmentId)
        if (shipment == null) {
            _state.update { it.copy(error = "Отгрузка не найдена локально") }
            return
        }
        val items = container.shipmentRepository.findItemsByShipment(shipmentId)
        val materials = items.map { item ->
            MaterialDraft(
                name = item.nameRaw,
                qty = item.qtyActual ?: item.qtyPlanned.orEmpty(),
                unit = item.unit,
                id = item.id,
                price = item.price,
                vatRate = item.vatRate,
                vatSum = item.vatSum,
            )
        }
        val sourceDocIds = RemoteMappers.decodeIdList(shipment.sourceDocumentIdsJson)
        container.sourceDocumentBackfillService.ensureCached(sourceDocIds)
        // Фото 1-го Этапа — для раскрывающегося блока «1 Этап» наверху формы.
        // Зеркало Stage2FormViewModel: тянем фото отгрузки и оставляем только
        // stage='before' (DispatchStage1FormViewModel помечает их именно так).
        val stage1RawPhotos = container.database.remoteShipmentDao()
            .findPhotosByShipment(shipmentId)
            .filter { it.stage == "before" }
        val stage1DocumentPhotos = stage1RawPhotos
            .filter { it.kind == "document" }
            .map { it.toStage1Item(captionLabel = "Документ") }
        val stage1VehiclePhotos = stage1RawPhotos
            .filterNot { it.kind == "document" }
            .map { it.toStage1Item(captionLabel = "Груз/машина") }
        val updDisplay = resolveUpdDisplay(sourceDocIds, shipment.comment)
        val siteName = resolveSiteName(shipment.siteId)
        val originalComment = shipment.comment.orEmpty()
        val parsed = parseShipmentComment(originalComment)
        // Тип транспорта — из локальной таблицы shipment_local_meta. На сервере
        // у shipment DTO такого поля нет; на 1 Этапе мы его записываем, на 2
        // Этапе восстанавливаем тут же. Зеркало Stage2FormViewModel:75.
        val vehicleTypeCode = container.shipmentRepository.getVehicleType(shipmentId)

        _state.update {
            it.copy(
                siteId = shipment.siteId,
                siteName = siteName,
                sourceDocumentIds = sourceDocIds,
                materials = materials,
                originalMaterials = materials,
                originalCommentText = parsed.stage2.orEmpty(),
                originalVehicleTypeCode = vehicleTypeCode,
                stage1Comment = parsed.stage1,
                inheritedNote = parsed.note,
                commentText = parsed.stage2.orEmpty(),
                vehicleTypeCode = vehicleTypeCode,
                vehiclePlate = shipment.vehiclePlate?.takeIf { p -> p.isNotBlank() },
                kind = shipment.kind,
                purpose = shipment.purpose,
                inTransit = shipment.inTransit,
                isAssets = shipment.isAssets,
                receiverCounterpartyId = shipment.receiverCounterpartyId,
                receiverMolId = shipment.receiverMolId,
                destSiteId = shipment.destSiteId,
                shippedAt = shipment.shippedAt,
                shippedAtMs = parseInstantToMs(shipment.shippedAt),
                stage1DocumentPhotos = stage1DocumentPhotos,
                stage1VehiclePhotos = stage1VehiclePhotos,
                driverName = shipment.driverName,
                updDisplay = updDisplay,
                loaded = true,
            )
        }
    }

    private fun parseInstantToMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    private fun com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity.toStage1Item(
        captionLabel: String,
    ): Stage1PhotoItem = Stage1PhotoItem(
        photoId = id,
        localBlobPath = localBlobPath,
        captionLabel = captionLabel,
        takenAtMs = parseInstantToMs(takenAt),
    )

    private suspend fun restoreDraftIfAny() {
        val draft = container.shipmentStage2DraftRepository.findById(shipmentId) ?: return
        val state = container.shipmentStage2DraftRepository.toState(draft)
        _state.update {
            it.copy(
                documentPhotoPaths = state.documentPhotoPaths,
                vehiclePhotoPaths = state.vehiclePhotoPaths,
                vehicleTypeCode = state.vehicleTypeCode,
                materials = state.materials,
                editedIndexes = state.editedIndexes,
                commentText = state.commentText,
            )
        }
    }

    private suspend fun observeAutoSave() {
        _state
            .drop(1)
            .debounce(300L)
            .distinctUntilChanged { a, b -> a.draftPayloadEquals(b) }
            .collect { snapshot ->
                if (snapshot.finalized || !snapshot.loaded) return@collect
                if (snapshot.hasUnsavedChanges()) {
                    container.shipmentStage2DraftRepository.upsert(snapshot.toDraftState())
                } else {
                    container.shipmentStage2DraftRepository.deleteById(shipmentId)
                }
            }
    }

    private fun DispatchStage2FormUiState.hasUnsavedChanges(): Boolean =
        materials != originalMaterials ||
            commentText != originalCommentText ||
            vehicleTypeCode != originalVehicleTypeCode ||
            documentPhotoPaths.isNotEmpty() ||
            vehiclePhotoPaths.isNotEmpty()

    private fun DispatchStage2FormUiState.toDraftState(): ShipmentStage2DraftState {
        val now = System.currentTimeMillis()
        return ShipmentStage2DraftState(
            shipmentId = shipmentId,
            documentPhotoPaths = documentPhotoPaths,
            vehiclePhotoPaths = vehiclePhotoPaths,
            vehicleTypeCode = vehicleTypeCode,
            materials = materials,
            editedIndexes = editedIndexes,
            commentText = commentText,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun resolveUpdDisplay(sourceDocIds: List<String>, comment: String?): String? {
        val docs = sourceDocIds.mapNotNull {
            runCatching { container.database.remoteSourceDocumentDao().findById(it) }.getOrNull()
        }
        val numbers = docs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
        if (numbers.isNotEmpty()) return numbers.joinToString(", ")
        return comment
            ?.let { Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$").find(it)?.groupValues?.getOrNull(1)?.trim() }
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveSiteName(siteId: String): String? {
        if (siteId.isBlank()) return null
        return runCatching {
            container.database.remoteSiteDao().findById(siteId)?.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun onDocumentPhotoTaken(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths + path) }
    }
    fun removeDocumentPhoto(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths - path) }
    }
    fun onVehiclePhotoTaken(path: String) {
        viewModelScope.launch {
            stampWatermark(path)
            _state.update { it.copy(vehiclePhotoPaths = it.vehiclePhotoPaths + path) }
        }
    }
    fun removeVehiclePhoto(path: String) {
        _state.update { it.copy(vehiclePhotoPaths = it.vehiclePhotoPaths - path) }
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
    fun addMaterial(draft: MaterialDraft) {
        _state.update { it.copy(materials = it.materials + draft) }
    }
    fun updateMaterial(index: Int, draft: MaterialDraft) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val newMaterials = cur.materials.toMutableList().also { it[index] = draft }
            cur.copy(materials = newMaterials, editedIndexes = cur.editedIndexes + index)
        }
    }
    fun deleteMaterial(index: Int) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val newMaterials = cur.materials.toMutableList().also { it.removeAt(index) }
            val newEdited = cur.editedIndexes
                .asSequence()
                .filter { it != index }
                .map { if (it > index) it - 1 else it }
                .toSet()
            val newOriginals = if (index in cur.originalMaterials.indices)
                cur.originalMaterials.toMutableList().also { it.removeAt(index) }
            else cur.originalMaterials
            cur.copy(
                materials = newMaterials,
                editedIndexes = newEdited,
                originalMaterials = newOriginals,
            )
        }
    }
    fun setComment(text: String) { _state.update { it.copy(commentText = text) } }

    /**
     * Правка номера УПД на 2 Этапе Выезда. Видна только когда УПД не была
     * подгружена с сервера на 1 Этапе (`sourceDocumentIds.isEmpty()`). Значение
     * попадает в существующий comment-формат `Примечание: ...` через
     * [buildCombinedComment] на финализации — отдельных полей в API и Room
     * не добавляем, парсинг на серверной стороне уже работает.
     *
     * Персистится в SavedStateHandle (а не в Room-draft), чтобы переживать
     * пересоздание Activity без миграции схемы. На уровне UiState `null`
     * означает «не редактировали», пустая строка — «инспектор очистил».
     */
    fun setInheritedNote(text: String) {
        val normalized = text.trim()
        savedStateHandle[KEY_INHERITED_NOTE] = normalized
        _state.update { it.copy(inheritedNote = normalized.takeIf { v -> v.isNotEmpty() }) }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    fun finalizeStage2() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized || !cur.loaded) return
        val siteId = cur.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет siteId, переоткройте отгрузку") }
            return
        }

        if (cur.vehiclePhotoPaths.isEmpty()) {
            _state.update { it.copy(error = "Добавьте фото машины") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val items = cur.materials
                    .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
                    .mapIndexed { idx, m ->
                        ShipmentRepository.ItemInput(
                            id = m.id,
                            nameRaw = m.name.trim().ifEmpty { "—" },
                            qtyActual = m.qty.trim().ifEmpty { null },
                            lineNo = idx + 1,
                            price = m.price,
                            vatRate = m.vatRate,
                            vatSum = m.vatSum,
                        )
                    }

                container.shipmentRepository.upsert(
                    ShipmentRepository.UpsertInput(
                        id = cur.shipmentId,
                        statusCode = "confirmed_mol",
                        kind = cur.kind ?: "contractor",
                        siteId = siteId,
                        receiverCounterpartyId = cur.receiverCounterpartyId,
                        receiverMolId = cur.receiverMolId,
                        destSiteId = cur.destSiteId,
                        vehiclePlate = cur.vehiclePlate,
                        driverName = cur.driverName,
                        shippedAt = cur.shippedAt,
                        // Момент нажатия «Завершить 2 Этап» — симметрия с
                        // приёмкой (Stage2FormViewModel).
                        confirmedByMolAt = java.time.Instant.now().toString(),
                        comment = buildCombinedComment(
                            stage1 = cur.stage1Comment,
                            stage2 = cur.commentText.trim().ifEmpty { null },
                            note = cur.inheritedNote,
                        ),
                        // Тип отгрузки read-only на 2 Этапе — передаём как-есть
                        // из server-snapshot, чтобы не обнулить при finalize.
                        purpose = cur.purpose,
                        // Транзит и ОС — read-only на 2 этапе, передаём как-есть.
                        inTransit = cur.inTransit,
                        isAssets = cur.isAssets,
                        sourceDocumentIds = cur.sourceDocumentIds,
                        items = items,
                    ),
                )

                // Сохраняем тип транспорта, если поменялся на 2 Этапе. Зеркало
                // Stage2FormViewModel:366. Локальное поле, на сервер не уходит.
                if (cur.vehicleTypeCode != cur.originalVehicleTypeCode) {
                    container.shipmentRepository.setVehicleType(cur.shipmentId, cur.vehicleTypeCode)
                }

                // Фото 2-го этапа отгрузки помечаем stage='after' — веб-портал
                // разделит «1 Этап / 2 Этап» в шапке отгрузки.
                val photoErrors = mutableListOf<String>()
                cur.documentPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForShipment(
                            shipmentId = cur.shipmentId,
                            kind = "document",
                            sourceUri = uri,
                            stage = "after",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("DispatchStage2", "document photo failed: $path", t)
                        photoErrors += "док ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }
                cur.vehiclePhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForShipment(
                            shipmentId = cur.shipmentId,
                            kind = "vehicle",
                            sourceUri = uri,
                            stage = "after",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("DispatchStage2", "vehicle photo failed: $path", t)
                        photoErrors += "машина ${File(path).name}: ${t.message ?: t::class.simpleName}"
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
                // Триггерим немедленный sync — без этого мутация stage2 сидит
                // в очереди до периодического Worker'а (15 мин) или network
                // callback'а. Зеркало Stage1/Stage2/DispatchStage1 finalize'ов.
                MatcheckSyncScheduler.requestImmediateSync(container.appContext)
                container.shipmentStage2DraftRepository.deleteById(shipmentId)
                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }

    private fun parseShipmentComment(raw: String): ParsedComment {
        if (raw.isBlank()) return ParsedComment(null, null, null)
        var stage1: String? = null
        var stage2: String? = null
        var note: String? = null
        val leftovers = mutableListOf<String>()
        raw.split('\n').forEach { line ->
            val s = line.trim()
            if (s.isEmpty()) return@forEach
            STAGE1_REGEX.find(s)?.let { stage1 = it.groupValues[1]; return@forEach }
            STAGE2_REGEX.find(s)?.let { stage2 = it.groupValues[1]; return@forEach }
            NOTE_REGEX.find(s)?.let { note = it.groupValues[1].trim(); return@forEach }
            leftovers += s
        }
        if (stage2 == null && leftovers.isNotEmpty()) stage2 = leftovers.joinToString("\n")
        return ParsedComment(stage1 = stage1, stage2 = stage2.orEmpty(), note = note)
    }

    private fun buildCombinedComment(stage1: String?, stage2: String?, note: String?): String? {
        val lines = buildList {
            if (!stage1.isNullOrBlank()) add("1 Этап: \"$stage1\"")
            if (!stage2.isNullOrBlank()) add("2 Этап: \"$stage2\"")
            if (!note.isNullOrBlank()) add("Примечание: $note")
        }
        return lines.joinToString("\n").ifEmpty { null }
    }

    private fun DispatchStage2FormUiState.draftPayloadEquals(other: DispatchStage2FormUiState): Boolean =
        materials == other.materials &&
            editedIndexes == other.editedIndexes &&
            commentText == other.commentText &&
            vehicleTypeCode == other.vehicleTypeCode &&
            documentPhotoPaths == other.documentPhotoPaths &&
            vehiclePhotoPaths == other.vehiclePhotoPaths

    private data class ParsedComment(val stage1: String?, val stage2: String?, val note: String?)

    private companion object {
        val STAGE1_REGEX = Regex("^1 Этап:\\s*\"(.*)\"$")
        val STAGE2_REGEX = Regex("^2 Этап:\\s*\"(.*)\"$")
        val NOTE_REGEX = Regex("^Примечание:\\s*(.+)$")
        /** Ключ для SavedStateHandle: переживает Activity recreation + process death. */
        const val KEY_INHERITED_NOTE = "inheritedNote"
    }
}

data class DispatchStage2FormUiState(
    val shipmentId: String,
    val siteId: String? = null,
    val siteName: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    val documentPhotoPaths: List<String> = emptyList(),
    val vehiclePhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    val originalMaterials: List<MaterialDraft> = emptyList(),
    val originalCommentText: String = "",
    /** vehicleTypeCode на момент загрузки — для определения «есть изменения». */
    val originalVehicleTypeCode: String? = null,
    val stage1Comment: String? = null,
    val inheritedNote: String? = null,
    val editedIndexes: Set<Int> = emptySet(),
    val commentText: String = "",
    val vehiclePlate: String? = null,
    val kind: String? = null,
    /** «Тип отгрузки» — server-snapshot, read-only на 2 Этапе. */
    val purpose: String? = null,
    /** Транзит — server-snapshot, read-only на 2 Этапе. */
    val inTransit: Boolean = false,
    /** ОС — server-snapshot, read-only на 2 Этапе (см. inTransit). */
    val isAssets: Boolean = false,
    val receiverCounterpartyId: String? = null,
    val receiverMolId: String? = null,
    val destSiteId: String? = null,
    val shippedAt: String? = null,
    /** Момент завершения 1-го Этапа в локальной зоне устройства (epoch-ms). */
    val shippedAtMs: Long? = null,
    /** Фото 1-го Этапа с `kind='document'` для read-only блока «1 Этап». */
    val stage1DocumentPhotos: List<com.example.matcheckmobile.presentation.components.Stage1PhotoItem> = emptyList(),
    /** Фото 1-го Этапа с любым `kind` кроме 'document'. */
    val stage1VehiclePhotos: List<com.example.matcheckmobile.presentation.components.Stage1PhotoItem> = emptyList(),
    val driverName: String? = null,
    val updDisplay: String? = null,
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
