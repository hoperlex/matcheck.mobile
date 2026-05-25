package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.repository.Stage2DraftState
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
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
 * Форма «2 Этап» — подтверждение МОЛ ранее оформленной приёмки. Открывает
 * существующую Delivery по [Routes.ARG_DELIVERY_ID], предзаполняет поля и
 * на финализации переводит статус с `filled` на `confirmed_mol`.
 *
 * Фото в этом этапе делятся на два потока, как и на 1 Этапе:
 * - «Фото документов» — без штампа, через document scanner.
 * - «Фото машины, госномера» — обычное фото с watermark (GPS / время / Объект).
 * Старые фото с сервера в редакторе не показываются (это отдельная задача).
 */
@OptIn(FlowPreview::class)
class Stage2FormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deliveryId: String =
        savedStateHandle[Routes.ARG_DELIVERY_ID]
            ?: error("Stage2FormViewModel: deliveryId required in route")

    private val _state = MutableStateFlow(Stage2FormUiState(deliveryId = deliveryId))
    val state: StateFlow<Stage2FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            loadInitial()
            restoreDraftIfAny()
            observeAutoSave()
        }
    }

    private suspend fun loadInitial() {
        val delivery = container.deliveryRepository.findById(deliveryId)
        if (delivery == null) {
            _state.update { it.copy(error = "Приёмка не найдена локально") }
            return
        }
        val items = container.deliveryRepository.findItemsByDelivery(deliveryId)
        val materials = items.map { item ->
            MaterialDraft(
                name = item.nameRaw,
                qty = item.qtyActual ?: item.qtyPlanned.orEmpty(),
                unit = item.unit,
            )
        }
        val vehicleTypeCode = container.deliveryRepository.getVehicleType(deliveryId)
        val sourceDocIds = RemoteMappers.decodeIdList(delivery.sourceDocumentIdsJson)
        // УПД, привязанные к приёмке, /sync не отдаёт — дотягиваем индивидуально,
        // чтобы [resolveUpdDisplay] увидел реальный docNumber в локальной БД.
        container.sourceDocumentBackfillService.ensureCached(sourceDocIds)
        val updDisplay = resolveUpdDisplay(sourceDocIds, delivery.comment)
        val siteName = resolveSiteName(delivery.siteId)
        val originalComment = delivery.comment.orEmpty()

        _state.update {
            it.copy(
                siteId = delivery.siteId,
                siteName = siteName,
                sourceDocumentIds = sourceDocIds,
                materials = materials,
                // Снимаем «снимок» серверного состояния — UI сравнивает текущие
                // materials с originalMaterials и подсвечивает изменения:
                // перечёркивает только реально изменённое название, qty
                // показывает как «old (new)».
                originalMaterials = materials,
                originalCommentText = originalComment,
                originalVehicleTypeCode = vehicleTypeCode,
                commentText = originalComment,
                vehicleTypeCode = vehicleTypeCode,
                vehiclePlate = delivery.vehiclePlate?.takeIf { p -> p.isNotBlank() },
                // Снимок серверных полей, которые мы не редактируем, но
                // обязаны вернуть на finalize-upsert (иначе сервер их обнулит).
                supplierId = delivery.supplierId,
                contractorId = delivery.contractorId,
                driverName = delivery.driverName,
                arrivedAt = delivery.arrivedAt,
                updDisplay = updDisplay,
                loaded = true,
            )
        }
    }

    /**
     * Если уже есть локальный draft по этому deliveryId — накладываем его
     * поверх серверного снимка. `originalMaterials/Comment/Vehicle` остаются
     * server-snapshot'ом: подсветка изменений в форме сравнивает текущее
     * значение именно с ним.
     */
    private suspend fun restoreDraftIfAny() {
        val draftEntity = container.stage2DraftRepository.findById(deliveryId) ?: return
        val draft = container.stage2DraftRepository.toState(draftEntity)
        _state.update {
            it.copy(
                documentPhotoPaths = draft.documentPhotoPaths,
                vehiclePhotoPaths = draft.vehiclePhotoPaths,
                vehicleTypeCode = draft.vehicleTypeCode,
                materials = draft.materials,
                editedIndexes = draft.editedIndexes,
                commentText = draft.commentText,
            )
        }
    }

    /**
     * Автосейв draft: подписка на state с debounce. Триггер — ЛЮБОЕ отличие
     * от серверного снимка (правка qty/наименования, новая позиция, новый
     * комментарий, выбор транспорта, добавление фото). Если все правки
     * откатили — draft удаляется.
     */
    private suspend fun observeAutoSave() {
        _state
            .drop(1)
            .debounce(300L)
            .distinctUntilChanged { a, b -> a.draftPayloadEquals(b) }
            .collect { snapshot ->
                if (snapshot.finalized || !snapshot.loaded) return@collect
                if (snapshot.hasUnsavedChanges()) {
                    container.stage2DraftRepository.upsert(snapshot.toDraftState())
                } else {
                    container.stage2DraftRepository.deleteById(deliveryId)
                }
            }
    }

    private fun Stage2FormUiState.hasUnsavedChanges(): Boolean =
        materials != originalMaterials ||
            commentText != originalCommentText ||
            vehicleTypeCode != originalVehicleTypeCode ||
            documentPhotoPaths.isNotEmpty() ||
            vehiclePhotoPaths.isNotEmpty()

    private fun Stage2FormUiState.toDraftState(): Stage2DraftState {
        val now = System.currentTimeMillis()
        return Stage2DraftState(
            deliveryId = deliveryId,
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

    /**
     * Та же логика, что в [Stage2ListViewModel]: сначала ищем номера привязанных
     * УПД в `remote_source_documents`, иначе вытаскиваем «УПД: …» из multiline-
     * комментария приёмки.
     */
    private suspend fun resolveUpdDisplay(
        sourceDocIds: List<String>,
        comment: String?,
    ): String? {
        val docs = sourceDocIds.mapNotNull {
            runCatching { container.database.remoteSourceDocumentDao().findById(it) }.getOrNull()
        }
        val numbers = docs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
        if (numbers.isNotEmpty()) return numbers.joinToString(", ")
        return comment
            ?.let { MANUAL_UPD_REGEX.find(it)?.groupValues?.getOrNull(1)?.trim() }
            ?.takeIf { it.isNotBlank() }
    }

    /** Имя объекта для штампа фото машины. Если не нашли — штамп напишет «Объект: —». */
    private suspend fun resolveSiteName(siteId: String): String? {
        if (siteId.isBlank()) return null
        return runCatching {
            container.database.remoteSiteDao().findById(siteId)?.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun onDocumentPhotoTaken(path: String) {
        // На фото документов штамп не накладываем — он перекрывал бы текст УПД.
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

    fun selectVehicle(code: String?) {
        _state.update { it.copy(vehicleTypeCode = code) }
    }

    fun setMaterials(drafts: List<MaterialDraft>) {
        _state.update { it.copy(materials = drafts) }
    }

    /**
     * Добавление строки материала вручную — на 2 Этапе инспектор может донабрать
     * позицию, отсутствующую в серверной приёмке (typical для приёмок без УПД).
     * editedIndexes не трогаем: «изменёнными» считаем правки существующих
     * серверных позиций, а свежая строка не имеет прежнего значения, перечёркивать
     * её название бессмысленно.
     */
    fun addMaterial(draft: MaterialDraft) {
        _state.update { it.copy(materials = it.materials + draft) }
    }

    /**
     * Правка одного материала в списке. Добавляем индекс в [editedIndexes],
     * чтобы UI знал, что строку нужно отметить перечёркнутым названием.
     */
    fun updateMaterial(index: Int, draft: MaterialDraft) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val newMaterials = cur.materials.toMutableList().also { it[index] = draft }
            cur.copy(
                materials = newMaterials,
                editedIndexes = cur.editedIndexes + index,
            )
        }
    }

    /**
     * Удаление строки. Индексы соседей справа сдвигаются на 1 влево —
     * пересчитываем [editedIndexes] и [originalMaterials] соответствующим
     * образом, чтобы подсветка остатков продолжала указывать на правильные
     * серверные снимки.
     */
    fun deleteMaterial(index: Int) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val newMaterials = cur.materials.toMutableList().also { it.removeAt(index) }
            val newEdited = cur.editedIndexes
                .asSequence()
                .filter { it != index }
                .map { if (it > index) it - 1 else it }
                .toSet()
            val newOriginals = if (index in cur.originalMaterials.indices) {
                cur.originalMaterials.toMutableList().also { it.removeAt(index) }
            } else {
                cur.originalMaterials
            }
            cur.copy(
                materials = newMaterials,
                editedIndexes = newEdited,
                originalMaterials = newOriginals,
            )
        }
    }

    fun setComment(text: String) {
        _state.update { it.copy(commentText = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun finalizeStage2() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized || !cur.loaded) return

        val siteId = cur.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет siteId, переоткройте приёмку") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val items = cur.materials
                    .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
                    .mapIndexed { idx, m ->
                        DeliveryRepository.ItemInput(
                            nameRaw = m.name.trim().ifEmpty { "—" },
                            qtyActual = m.qty.trim().ifEmpty { null },
                            lineNo = idx + 1,
                        )
                    }

                container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        id = cur.deliveryId,
                        statusCode = "confirmed_mol",
                        siteId = siteId,
                        // Все «не-редактируемые на 2 Этапе» поля приёмки
                        // (Госномер / Прибытие / Поставщик / Подрядчик / Водитель)
                        // обязаны быть переданы обратно, иначе сервер запишет
                        // null'ы и веб-портал покажет пустые колонки в
                        // карточке «Подтверждено МОЛ».
                        supplierId = cur.supplierId,
                        contractorId = cur.contractorId,
                        vehiclePlate = cur.vehiclePlate,
                        driverName = cur.driverName,
                        arrivedAt = cur.arrivedAt,
                        comment = cur.commentText.ifBlank { null },
                        sourceDocumentIds = cur.sourceDocumentIds,
                        items = items,
                    ),
                )

                container.deliveryRepository.setVehicleType(cur.deliveryId, cur.vehicleTypeCode)

                val photoErrors = mutableListOf<String>()
                cur.documentPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = cur.deliveryId,
                            kind = "document",
                            sourceUri = uri,
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("Stage2", "document photo capture failed: $path", t)
                        photoErrors += "док ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }
                cur.vehiclePhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = cur.deliveryId,
                            kind = "vehicle",
                            sourceUri = uri,
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("Stage2", "vehicle photo capture failed: $path", t)
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

                // Принудительный push: без него мутация и новые фото 2 Этапа
                // ждали бы периодики WorkManager (минимум 15 минут) или SSE-триггера.
                // Stage1 уже делает то же самое после своего finalize.
                MatcheckSyncScheduler.requestImmediateSync(container.appContext)

                // Финализировано → draft больше не нужен. Удаляем ДО finalized=true,
                // чтобы автосейв не воскресил запись на следующем тике debounce.
                container.stage2DraftRepository.deleteById(deliveryId)
                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }

    /**
     * Полезная нагрузка state'а для distinctUntilChanged автосейва. Исключаем
     * волатильные UI-поля (isSaving, finalized, error, loaded, siteName и т.п.).
     */
    private fun Stage2FormUiState.draftPayloadEquals(other: Stage2FormUiState): Boolean =
        materials == other.materials &&
            editedIndexes == other.editedIndexes &&
            commentText == other.commentText &&
            vehicleTypeCode == other.vehicleTypeCode &&
            documentPhotoPaths == other.documentPhotoPaths &&
            vehiclePhotoPaths == other.vehiclePhotoPaths

    private companion object {
        val MANUAL_UPD_REGEX = Regex("(?m)^УПД:\\s*(.+)$")
    }
}

data class Stage2FormUiState(
    val deliveryId: String,
    val siteId: String? = null,
    val siteName: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    val documentPhotoPaths: List<String> = emptyList(),
    val vehiclePhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    /**
     * Серверный снимок materials на момент загрузки приёмки. UI сравнивает с
     * текущим [materials] и подсвечивает разницу: перечёркнутое название
     * (если name изменилось), qty в формате «old (new)» (если qty изменилось).
     * Для строк, добавленных вручную через «+ Добавить материал», оригинала
     * нет — индекс выходит за пределы [originalMaterials].
     */
    val originalMaterials: List<MaterialDraft> = emptyList(),
    /** Серверный комментарий на момент загрузки — для определения «есть изменения». */
    val originalCommentText: String = "",
    /** Серверный vehicleTypeCode на момент загрузки — для определения «есть изменения». */
    val originalVehicleTypeCode: String? = null,
    /** Индексы материалов, которые правил инспектор на 2 Этапе — UI отмечает их перечёркнутым названием. */
    val editedIndexes: Set<Int> = emptySet(),
    val commentText: String = "",
    /** Госномер из приёмки — read-only сводка для информационного блока. */
    val vehiclePlate: String? = null,
    // Серверные поля приёмки, которые 2 Этап не редактирует, но обязан
    // вернуть их назад при finalize-upsert. Если их не передать,
    // updateDelivery на сервере перепишет колонки в `null` (см.
    // apps/api/src/routes/deliveries.ts: vehiclePlate: input.vehiclePlate ?? null
    // и т.д.) — и веб-портал в «Подтверждено МОЛ» покажет пустые
    // Госномер / Прибытие / Поставщик / Подрядчик / Водитель.
    val supplierId: String? = null,
    val contractorId: String? = null,
    val driverName: String? = null,
    val arrivedAt: String? = null,
    /** Номер(а) привязанной УПД для сводки, либо ручной номер из comment. */
    val updDisplay: String? = null,
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
