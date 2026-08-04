package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.repository.ManualEntryDraftState
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Ручной внос» — приёмка без УПД и автотранспорта.
 *
 * Инспектор делает фото (документы + груз), указывает номер УПД текстом,
 * материалы и комментарий. Форма работает «по draftId»: сначала на
 * [ManualEntryListScreen] создаётся локальный черновик, форма его
 * дозаполняет с автосейвом. По «Завершить» приёмка создаётся в статусе
 * `confirmed_mol` (на веб-портале — «Принятые / Подтверждено МОЛ», тег «Без
 * документа», sourceDocumentIds=[]), а черновик удаляется. До «Завершить»
 * запись живёт только локально и на сервер не уходит.
 *
 * Отличия от Stage1FormViewModel:
 *  - нет vehiclePlate, vehicleTypeCode, inTransit (это не автотранспорт);
 *  - нет загрузки УПД (sourceDocumentIds всегда пуст);
 *  - statusCode на финализе = 'confirmed_mol' (а не 'filled').
 */
@OptIn(FlowPreview::class)
class ManualEntryFormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val draftLocalId: String = requireNotNull(savedStateHandle.get<String>(Routes.ARG_DRAFT_ID)) {
        "ManualEntryFormViewModel requires ${Routes.ARG_DRAFT_ID} nav arg"
    }

    /** Объект черновика — фиксируется на создании; finalize шлёт запись именно сюда. */
    private var draftSiteId: String? = null

    private val _state = MutableStateFlow(ManualEntryFormUiState())
    val state: StateFlow<ManualEntryFormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restoreDraft() }
        viewModelScope.launch { observeAutoSave() }
    }

    private suspend fun restoreDraft() {
        container.manualEntryDraftRepository.findById(draftLocalId)?.let { entity ->
            draftSiteId = entity.siteId
            val restored = container.manualEntryDraftRepository.toState(entity)
            _state.update {
                it.copy(
                    documentPhotoPaths = restored.documentPhotoPaths,
                    cargoPhotoPaths = restored.cargoPhotoPaths,
                    manualUpdText = restored.manualUpdText,
                    materials = restored.materials,
                    commentText = restored.commentText,
                    isAssets = restored.isAssets,
                )
            }
        }
        _state.update { it.copy(loaded = true) }
    }

    /**
     * Автосейв черновика: подписка на state с debounce. `drop(1)` — чтобы не
     * дёрнуть запись дефолтным пустым state до restoreDraft. Строка черновика
     * уже создана списком (эйджер), поэтому просто upsert'им актуальные поля;
     * очистка пустого черновика — в [onLeave].
     */
    private suspend fun observeAutoSave() {
        _state
            .drop(1)
            .debounce(300L)
            .distinctUntilChanged { a, b -> a.draftPayloadEquals(b) }
            .collect { snapshot ->
                if (!snapshot.loaded || snapshot.finalized) return@collect
                val siteId = draftSiteId ?: return@collect
                container.manualEntryDraftRepository.upsert(snapshot.toDraftState(siteId))
            }
    }

    /**
     * Уход с формы (back-arrow / системный back). Если черновик содержателен —
     * дозаписываем актуальное состояние (страховка от того, что последний
     * debounce автосейва не успел сработать до pop). Если пуст — удаляем строку
     * и её temp-фото, чтобы список не засорялся отменёнными операциями.
     *
     * Выполняется в [AppContainer.appScope], т.к. `after()` дёргает popBackStack
     * и viewModelScope к моменту записи уже отменён.
     */
    fun onLeave(after: () -> Unit) {
        val cur = _state.value
        val siteId = draftSiteId
        // Только после loaded: до окончания restoreDraft `cur` ещё дефолтно-пуст,
        // и удаление приняло бы переоткрытый содержательный черновик за пустой.
        if (cur.loaded && !cur.finalized && siteId != null) {
            container.appScope.launch {
                if (cur.isPristine()) {
                    (cur.documentPhotoPaths + cur.cargoPhotoPaths).forEach { path ->
                        runCatching { File(path).delete() }
                    }
                    container.manualEntryDraftRepository.deleteById(draftLocalId)
                } else {
                    container.manualEntryDraftRepository.upsert(cur.toDraftState(siteId))
                }
            }
        }
        after()
    }

    /** Проекция UI-state → сохраняемый черновик. createdAt подменит repo.upsert из БД. */
    private fun ManualEntryFormUiState.toDraftState(siteId: String): ManualEntryDraftState {
        val now = System.currentTimeMillis()
        return ManualEntryDraftState(
            localDraftId = draftLocalId,
            siteId = siteId,
            documentPhotoPaths = documentPhotoPaths,
            cargoPhotoPaths = cargoPhotoPaths,
            manualUpdText = manualUpdText,
            materials = materials,
            commentText = commentText,
            isAssets = isAssets,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun addDocumentPhoto(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths + path) }
    }

    fun removeDocumentPhoto(path: String) {
        _state.update {
            it.copy(documentPhotoPaths = it.documentPhotoPaths.filter { p -> p != path })
        }
    }

    fun addCargoPhoto(path: String) {
        _state.update { it.copy(cargoPhotoPaths = it.cargoPhotoPaths + path) }
    }

    fun removeCargoPhoto(path: String) {
        _state.update {
            it.copy(cargoPhotoPaths = it.cargoPhotoPaths.filter { p -> p != path })
        }
    }

    fun setManualUpd(text: String) {
        _state.update { it.copy(manualUpdText = text) }
    }

    fun setMaterials(materials: List<MaterialDraft>) {
        _state.update { it.copy(materials = materials) }
    }

    /** Добавление новой строки материала (через MaterialEditDialog). */
    fun addMaterial(draft: MaterialDraft) {
        _state.update { it.copy(materials = it.materials + draft) }
    }

    /** Правка строки по индексу. */
    fun updateMaterial(index: Int, draft: MaterialDraft) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val next = cur.materials.toMutableList().also { it[index] = draft }
            cur.copy(materials = next)
        }
    }

    /** Удаление строки по индексу (свайп вправо). */
    fun deleteMaterial(index: Int) {
        _state.update { cur ->
            if (index !in cur.materials.indices) return@update cur
            val next = cur.materials.toMutableList().also { it.removeAt(index) }
            cur.copy(materials = next)
        }
    }

    fun setComment(text: String) {
        _state.update { it.copy(commentText = text) }
    }

    /** ОС — чекбокс «основные средства» на ручном внесе. */
    fun setIsAssets(value: Boolean) {
        _state.update { it.copy(isAssets = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun finalize() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized) return

        // siteId берём из черновика: приёмка уходит на тот объект, где начата,
        // даже если сессия позже переключилась. Fallback — текущий siteId.
        val siteId = draftSiteId ?: container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        // Фото груза обязательно — это основной артефакт ручного вноса,
        // без него карточка на веб-портале выглядит пустой и не даёт
        // менеджеру подтвердить факт приёмки.
        if (cur.cargoPhotoPaths.isEmpty()) {
            _state.update { it.copy(error = "Добавьте фото груза") }
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
                            unit = m.unit.ifEmpty { "шт" },
                            lineNo = idx + 1,
                            price = null,
                            vatRate = null,
                            vatSum = null,
                        )
                    }

                // Комментарий формата зеркального Stage1: «1 Этап: "<comment>"»
                // + «Примечание: <UPD номер>». На веб-портале это видно
                // в комментариях карточки. Бейдж «Ручной внос: …» — спереди,
                // чтобы менеджер сразу понимал контекст.
                val userComment = cur.commentText.trim()
                val manualUpd = cur.manualUpdText.trim()
                val commentParts = buildList {
                    add("Ручной внос")
                    if (userComment.isNotEmpty()) add("Комментарий: \"$userComment\"")
                    if (manualUpd.isNotEmpty()) add("Примечание: $manualUpd")
                }
                val commentForServer = commentParts.joinToString("\n")

                // Одно время на оба поля: для ручного вноса «прибытие» и
                // «подтверждение» — это один и тот же момент нажатия
                // «Завершить». Репозиторий сделает его липким, поэтому повтор
                // после ошибки фото время не сдвинет.
                val operationAt = java.time.Instant.now().toString()
                val deliveryId = container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        // Стабильный id вместо генерации нового на каждый вызов:
                        // natural-key dedup для empty-draft'ов сознательно не
                        // работает (см. DeliveryRepository.upsert), поэтому
                        // повтор после ошибки фото заводил вторую приёмку.
                        // draftLocalId — уже UUID и переживает перезапуск.
                        id = draftLocalId,
                        // Сразу confirmed_mol: сервер в createDelivery видит
                        // status='confirmed_mol' и заполняет confirmedByMol*
                        // из inspectorId, а время берёт присланное.
                        statusCode = "confirmed_mol",
                        siteId = siteId,
                        // Все «автотранспортные» поля null — это ручной внос.
                        supplierId = null,
                        contractorId = null,
                        recipientMolId = null,
                        vehiclePlate = null,
                        arrivedAt = operationAt,
                        confirmedByMolAt = operationAt,
                        comment = commentForServer,
                        inTransit = false,
                        // ОС — флаг «основные средства», берём прямо из state.
                        // Уходит в DeliveryUpsertRequest.isAssets, на сервере —
                        // deliveries.is_assets (миграция 0065).
                        isAssets = cur.isAssets,
                        sourceDocumentIds = emptyList(),
                        items = items,
                    ),
                )

                val photoErrors = mutableListOf<String>()
                // Фото с stage='before' — на веб-портале они попадут под
                // секцию «1 Этап». «2 Этап» останется пустым (для ручного
                // внеса второго этапа нет — инспектор уже подтвердил собой).
                cur.documentPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = deliveryId,
                            kind = "document",
                            sourceUri = uri,
                            stage = "before",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("ManualEntry", "document photo failed: $path", t)
                        photoErrors += "док ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }
                cur.cargoPhotoPaths.forEach { path ->
                    try {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = deliveryId,
                            kind = "cargo",
                            sourceUri = uri,
                            stage = "before",
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("ManualEntry", "cargo photo failed: $path", t)
                        photoErrors += "груз ${File(path).name}: ${t.message ?: t::class.simpleName}"
                    }
                }

                MatcheckSyncScheduler.requestImmediateSync(container.appContext)
                if (photoErrors.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            error = "Не удалось добавить фото: ${photoErrors.joinToString("; ")}",
                        )
                    }
                } else {
                    // Финализировано → черновик больше не нужен. Удаляем ДО
                    // выставления finalized=true, чтобы автосейв не воскресил
                    // строку на следующем тике (см. Stage1FormViewModel).
                    container.manualEntryDraftRepository.deleteById(draftLocalId)
                    _state.update { it.copy(isSaving = false, finalized = true) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ManualEntry", "finalize failed", t)
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = "Не удалось сохранить: ${t.message ?: t::class.simpleName}",
                    )
                }
            }
        }
    }
}

data class ManualEntryFormUiState(
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val manualUpdText: String = "",
    val materials: List<MaterialDraft> = emptyList(),
    val commentText: String = "",
    /** ОС — чекбокс «основные средства». На finalize → DeliveryRepository.UpsertInput.isAssets. */
    val isAssets: Boolean = false,
    /** true после restoreDraft — гейт для автосейва, чтобы не писать до загрузки. */
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

/** Пусто ли содержимое черновика — по такому уходим с формы без сохранения строки. */
private fun ManualEntryFormUiState.isPristine(): Boolean =
    documentPhotoPaths.isEmpty() && cargoPhotoPaths.isEmpty() &&
        materials.none { it.name.isNotBlank() || it.qty.isNotBlank() } &&
        commentText.isBlank() && manualUpdText.isBlank() && !isAssets

/**
 * Сравнение «полезной нагрузки» для distinctUntilChanged автосейва: исключаем
 * волатильные loaded/isSaving/finalized/error, чтобы не дёргать запись на них.
 */
private fun ManualEntryFormUiState.draftPayloadEquals(other: ManualEntryFormUiState): Boolean =
    documentPhotoPaths == other.documentPhotoPaths &&
        cargoPhotoPaths == other.cargoPhotoPaths &&
        manualUpdText == other.manualUpdText &&
        materials == other.materials &&
        commentText == other.commentText &&
        isAssets == other.isAssets
