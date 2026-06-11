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
                id = item.id,
                price = item.price,
                vatRate = item.vatRate,
                vatSum = item.vatSum,
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
        val parsed = parseDeliveryComment(originalComment)

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
                // originalCommentText сравниваем с commentText (только 2 Этап),
                // поэтому храним именно распарсенный stage2-текст.
                originalCommentText = parsed.stage2.orEmpty(),
                originalVehicleTypeCode = vehicleTypeCode,
                stage1Comment = parsed.stage1,
                inheritedNote = parsed.note,
                // commentText (поле «Комментарий» на 2 Этапе) — только текст
                // 2 Этапа. Текст 1 Этапа показывается отдельным read-only
                // блоком над инпутом; «Примечание: …» переносится как есть
                // в финальный комментарий при finalize, не показывается в инпуте.
                commentText = parsed.stage2.orEmpty(),
                vehicleTypeCode = vehicleTypeCode,
                vehiclePlate = delivery.vehiclePlate?.takeIf { p -> p.isNotBlank() },
                // Снимок серверных полей, которые мы не редактируем, но
                // обязаны вернуть на finalize-upsert (иначе сервер их обнулит).
                supplierId = delivery.supplierId,
                contractorId = delivery.contractorId,
                recipientMolId = delivery.recipientMolId,
                inTransit = delivery.inTransit,
                isAssets = delivery.isAssets,
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
                            id = m.id,
                            nameRaw = m.name.trim().ifEmpty { "—" },
                            qtyActual = m.qty.trim().ifEmpty { null },
                            lineNo = idx + 1,
                            price = m.price,
                            vatRate = m.vatRate,
                            vatSum = m.vatSum,
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
                        recipientMolId = cur.recipientMolId,
                        vehiclePlate = cur.vehiclePlate,
                        driverName = cur.driverName,
                        arrivedAt = cur.arrivedAt,
                        comment = buildCombinedComment(
                            stage1 = cur.stage1Comment,
                            stage2 = cur.commentText.trim().ifEmpty { null },
                            note = cur.inheritedNote,
                        ),
                        // Транзит и ОС — read-only на 2 этапе, передаём из
                        // server-snapshot, чтобы не обнулить при upsert.
                        inTransit = cur.inTransit,
                        isAssets = cur.isAssets,
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
                            stage = "after",
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
                            stage = "after",
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

    /**
     * Парсит серверный comment в три «слота»:
     *  - stage1 — текст из строки `1 Этап: "..."`,
     *  - stage2 — текст из строки `2 Этап: "..."` (если уже сохраняли),
     *  - note   — содержимое `Примечание: ...` (унаследованное от 1 Этапа).
     *
     * Любые «прочие» строки (старые комментарии без префикса) приклеиваются
     * к stage2 как fallback — иначе при первом сохранении 2 Этапа пользователь
     * потерял бы текст, который ввели до миграции на новый формат.
     */
    private fun parseDeliveryComment(raw: String): ParsedComment {
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
        if (stage2 == null && leftovers.isNotEmpty()) {
            stage2 = leftovers.joinToString("\n")
        }
        return ParsedComment(stage1 = stage1, stage2 = stage2.orEmpty(), note = note)
    }

    /** Собирает финальный comment для upsert на «Завершить 2 Этап». */
    private fun buildCombinedComment(stage1: String?, stage2: String?, note: String?): String? {
        val lines = buildList {
            if (!stage1.isNullOrBlank()) add("1 Этап: \"$stage1\"")
            if (!stage2.isNullOrBlank()) add("2 Этап: \"$stage2\"")
            if (!note.isNullOrBlank()) add("Примечание: $note")
        }
        return lines.joinToString("\n").ifEmpty { null }
    }

    private data class ParsedComment(
        val stage1: String?,
        val stage2: String?,
        val note: String?,
    )

    private companion object {
        // Stage1FormViewModel пишет ручную метку как «Примечание: ...» (если
        // у приёмки нет привязанной УПД), но кое-где использовалось «УПД: ...».
        // Принимаем оба варианта — единый формат с DispatchStage2FormViewModel
        // и обоими List-VM, иначе fallback-резолв тайтла молча промахивался.
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")
        val STAGE1_REGEX = Regex("^1 Этап:\\s*\"(.*)\"$")
        val STAGE2_REGEX = Regex("^2 Этап:\\s*\"(.*)\"$")
        val NOTE_REGEX = Regex("^Примечание:\\s*(.+)$")
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
    /**
     * Серверный комментарий 1 Этапа: текст между кавычками после префикса
     * «1 Этап: "..."». Показывается на форме 2 Этапа отдельным read-only
     * блоком над полем «Комментарий». null — если 1 Этап не оставлял
     * комментария.
     */
    val stage1Comment: String? = null,
    /**
     * Унаследованная строка «Примечание: …» из comment'а 1 Этапа (ручной
     * ввод номера УПД, когда УПД из списка не выбиралась). При finalize
     * 2 Этапа возвращается в итоговый comment без изменений.
     */
    val inheritedNote: String? = null,
    /** Комментарий, который пользователь вводит на 2 Этапе. */
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
    val recipientMolId: String? = null,
    val driverName: String? = null,
    val arrivedAt: String? = null,
    /** Транзит — read-only снимок с сервера на 2 этапе. */
    val inTransit: Boolean = false,
    /** ОС — read-only снимок с сервера на 2 этапе (см. inTransit). */
    val isAssets: Boolean = false,
    /** Номер(а) привязанной УПД для сводки, либо ручной номер из comment. */
    val updDisplay: String? = null,
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
