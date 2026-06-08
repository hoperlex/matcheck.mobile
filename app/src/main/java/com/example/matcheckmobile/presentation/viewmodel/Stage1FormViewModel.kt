package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.repository.Stage1DraftState
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
 * Упрощённая форма «1 Этап» приёмки: фото + тип транспорта (локально) +
 * материалы (список название/количество) + комментарий. Сохранение создаёт
 * Delivery со статусом `filled` через [DeliveryRepository.upsert], локальный
 * vehicleTypeCode пишется в delivery_local_meta, фото ставятся в очередь
 * загрузки через [com.example.matcheckmobile.data.repository.PhotoRepository.captureForDelivery].
 *
 * updId передаётся через NavGraph как опциональный аргумент — если есть,
 * приёмка привязывается к выбранной УПД через sourceDocumentIds, а позиции
 * УПД предзаполняются в materials (name + qty).
 */
@OptIn(FlowPreview::class)
class Stage1FormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val updId: String? = savedStateHandle[Routes.ARG_UPD_ID]
    private val initialDraftId: String? = savedStateHandle[Routes.ARG_DRAFT_ID]

    /**
     * Идентификатор draft в Room. Заранее сгенерирован: при первом фото
     * автосейв создаст запись с этим id, а навигация по списку «Сегодня»
     * вернёт пользователя сюда же по `draftId`.
     */
    private val draftLocalId: String = initialDraftId ?: UUID.randomUUID().toString()

    private val _state = MutableStateFlow(Stage1FormUiState(updId = updId))
    val state: StateFlow<Stage1FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restoreDraftOrInitDefaults() }
        viewModelScope.launch { resolveSiteName() }
        viewModelScope.launch { observeAutoSave() }
    }

    /**
     * Восстановление draft. Приоритет:
     * 1. По переданному `draftId` (открыт через «Сегодня» → форма уже была начата).
     * 2. По `updId` (заходит впервые из списка УПД, draft мог остаться от прошлой сессии).
     * 3. Иначе — обычный init с предзаполнением позициями из УПД.
     */
    private suspend fun restoreDraftOrInitDefaults() {
        val restored = when {
            initialDraftId != null -> container.stage1DraftRepository.findById(initialDraftId)
            !updId.isNullOrBlank() -> container.stage1DraftRepository.findByUpdId(updId)
            else -> null
        }?.let { container.stage1DraftRepository.toState(it) }

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
                    inTransit = restored.inTransit,
                )
            }
        }

        // УПД-метаданные (контрагенты, финансы по позициям) подтягиваем всегда,
        // если есть updId — даже когда восстановили draft. Materials заполним
        // из УПД только если draft пустой/отсутствует, чтобы не перетереть
        // пользовательский ввод.
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

        // Контрагенты — supplier/contractor/recipientMol — нужны на upsert,
        // чтобы веб-портал отрисовал их в карточке приёмки. SourceDocument
        // на сервере отдаёт recipientMolId отдельно (поле физлица-МОЛ),
        // recipientId — старое историческое поле, не используем.
        if (docMeta != null) {
            _state.update {
                it.copy(
                    updSupplierId = docMeta.supplierId,
                    updContractorId = docMeta.contractorId,
                    updRecipientMolId = docMeta.recipientMolId,
                    // Телефон менеджера, загрузившего УПД. null для УПД из EDO/mail
                    // и для тех, что загружены до серверной миграции 0039 — в этом
                    // случае иконка звонка в шапке модалки «Материалы» не рисуется.
                    managerPhone = docMeta.createdByUserPhone,
                )
            }
        }

        if (items.isEmpty()) return

        // Финансы из УПД сохраняем по lineNo — потом мерджим обратно при
        // финализации, даже если юзер правил qty/name (вьюшка их не показывает,
        // но цена и НДС не меняются от ручной правки количества).
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
        // На веб-портале volumeM3 и massKg у позиции УПД хранятся
        // ПО ЕДИНИЦЕ материала — поэтому суммируем как qty * value.
        // Отдельно трекаем hasVolume/hasMass: если ни у одной
        // позиции нет данных, ставим null, и gauge нарисуется как
        // «нет данных» (см. apps/web/.../VehicleFillGauge.tsx).
        var volume = 0.0
        var massKg = 0.0
        var hasVolume = false
        var hasMass = false
        for (item in items) {
            val qty = item.qty.toDoubleOrNull() ?: 0.0
            item.volumeM3?.toDoubleOrNull()?.let {
                volume += it * qty
                hasVolume = true
            }
            item.massKg?.toDoubleOrNull()?.let {
                massKg += it * qty
                hasMass = true
            }
        }
        val gauge = if (hasVolume || hasMass) VehicleLoadInfo(
            totalVolumeM3 = if (hasVolume) volume else null,
            totalMassT = if (hasMass) massKg / 1000.0 else null,
        ) else null
        _state.update { current ->
            val withMaterials = if (fillMaterials && current.materials.isEmpty())
                current.copy(materials = drafts)
            else current
            withMaterials.copy(
                loadInfo = gauge,
                materialFinancials = financials,
            )
        }
    }

    /**
     * Автосейв draft: подписка на state c debounce. Триггер — хотя бы одно
     * фото (любой kind). Если фото нет — draft удаляется (или не создаётся).
     * `drop(1)` чтобы при первой подписке не дёрнуть запись с дефолтным
     * пустым state до того, как успеет отработать restoreDraftOrInitDefaults.
     */
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
                    container.stage1DraftRepository.upsert(snapshot.toDraftState())
                } else {
                    container.stage1DraftRepository.deleteById(draftLocalId)
                }
            }
    }

    private fun Stage1FormUiState.toDraftState(): Stage1DraftState {
        val now = System.currentTimeMillis()
        // createdAt = now → используется только при первом upsert (когда строки
        // ещё нет в БД); на последующих save Stage1DraftRepository.upsert сам
        // подменит на сохранённый из БД, чтобы счётчик «>2ч» не сбрасывался.
        return Stage1DraftState(
            localDraftId = draftLocalId,
            updId = updId,
            documentPhotoPaths = documentPhotoPaths,
            cargoPhotoPaths = cargoPhotoPaths,
            vehicleTypeCode = vehicleTypeCode,
            materials = materials,
            commentText = commentText,
            licensePlate = licensePlate,
            manualUpdText = manualUpdText,
            inTransit = inTransit,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Тянет название объекта для штампа: сперва из выбранной УПД (поле siteName
     * из RemoteSourceDocument), иначе — из таблицы сайтов по siteId сессии.
     * Если ничего не нашлось — оставляем null, штамп напишет «Объект: —».
     */
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

        if (resolved != null) {
            _state.update { it.copy(siteName = resolved) }
        }
    }

    fun onDocumentPhotoTaken(path: String) {
        // На фото документов штамп не накладываем: он перекрывал текст УПД
        // в правом нижнем углу и мешал чтению.
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

    /**
     * Накладывает на JPEG штамп с GPS / временем / siteId. Координаты пытаемся
     * взять «последние известные» из FusedLocationProviderClient — без свежего
     * fix-а, чтобы юзер не ждал GPS. Если разрешения нет / GPS пуст — штамп
     * напишет «GPS: нет».
     */
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

    fun setComment(text: String) {
        _state.update { it.copy(commentText = text) }
    }

    fun setLicensePlate(text: String) {
        _state.update { it.copy(licensePlate = text, showPlateError = false) }
    }

    /** Чекбокс «Транзит» — см. Stage1FormUiState.inTransit. */
    fun setInTransit(value: Boolean) {
        _state.update { it.copy(inTransit = value) }
    }

    fun setManualUpd(text: String) {
        _state.update { it.copy(manualUpdText = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun finalizeStage1() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized) return

        val plate = cur.licensePlate.trim()
        if (plate.isBlank()) {
            _state.update { it.copy(showPlateError = true, error = "Введите госномер") }
            return
        }
        // Regex-валидация формата убрана намеренно: спецтехника / приcпы /
        // ведомственные номера используют нетипичные комбинации, и инспектору
        // важно завершить 1 Этап даже с «нестандартным» номером. Достаточно
        // проверки на непустоту.

        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                // Мерджим финансы из УПД (price/vatRate/vatSum) по lineNo —
                // юзер их не правит, но веб ждёт их рядом с позициями приёмки.
                val financialsByLine = cur.materialFinancials.associateBy { it.lineNo }
                val items = cur.materials
                    .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
                    .mapIndexed { idx, m ->
                        val lineNo = idx + 1
                        val fin = financialsByLine[lineNo]
                        DeliveryRepository.ItemInput(
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

                val plate = cur.licensePlate.trim()
                val userComment = cur.commentText.trim()
                val manualUpd = cur.manualUpdText.trim()
                // Госномер передаём отдельным полем vehiclePlate. В comment
                // комментарий 1 Этапа маркируем префиксом «1 Этап: "..."» —
                // на 2 Этапе VM парсит его и показывает отдельным блоком,
                // а при finalize дописывает строку «2 Этап: "..."». В итоге
                // веб-портал видит обе строки в одном поле «Комментарий».
                // Ручной номер УПД складываем в отдельную строку «Примечание: …»
                // — диспетчер использует её для ручной привязки УПД.
                val commentParts = buildList {
                    if (userComment.isNotEmpty()) add("1 Этап: \"$userComment\"")
                    if (manualUpd.isNotEmpty() && cur.updId == null) add("Примечание: $manualUpd")
                }
                val commentForServer = commentParts.joinToString("\n").ifEmpty { null }

                // CHECK constraint deliveries_recipient_chk на сервере:
                // нельзя одновременно contractor_id и recipient_mol_id. На вебе
                // в карточке это «Получатель: Подрядчик / МОЛ» — переключатель.
                // Приоритет МОЛ (физлица): если из УПД есть recipientMolId,
                // он перебивает contractorId; иначе используем contractorId.
                val finalRecipientMolId = cur.updRecipientMolId
                val finalContractorId = if (finalRecipientMolId == null) cur.updContractorId else null

                val deliveryId = container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        statusCode = "filled",
                        siteId = siteId,
                        // Контрагенты из УПД — без них веб показывает прочерки
                        // в Поставщике/Подрядчике/Получателе. Для no_document
                        // приёмок (cur.updId == null) полей нет, они остаются null.
                        supplierId = cur.updSupplierId,
                        contractorId = finalContractorId,
                        recipientMolId = finalRecipientMolId,
                        vehiclePlate = plate.ifEmpty { null },
                        // arrivedAt = момент «Завершить 1 Этап». Без него на веб-портале
                        // в колонке «Прибытие» висит «—»: сервер сам не подставляет default,
                        // а веб-форма делает это только при создании из своего UI.
                        arrivedAt = java.time.Instant.now().toString(),
                        comment = commentForServer,
                        inTransit = cur.inTransit,
                        sourceDocumentIds = sourceDocIds,
                        items = items,
                    ),
                )

                container.deliveryRepository.setVehicleType(deliveryId, cur.vehicleTypeCode)

                // Раньше тут было runCatching{}, и любая ошибка prepareFromUri
                // (decode/read/missing file) уходила в /dev/null — фото
                // не появлялись ни в БД, ни в очереди. Теперь аккумулируем
                // и показываем пользователю.
                val photoErrors = mutableListOf<String>()
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
                        android.util.Log.e("Stage1", "document photo capture failed: $path", t)
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
                        android.util.Log.e("Stage1", "cargo photo capture failed: $path", t)
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

                // Финализировано → draft больше не нужен. Делаем это ДО
                // выставления finalized=true, чтобы автосейв не успел
                // воскресить запись на следующем тике.
                container.stage1DraftRepository.deleteById(draftLocalId)
                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }
}

data class Stage1FormUiState(
    val updId: String? = null,
    val siteName: String? = null,
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    // Финансы по позициям УПД (price/vatRate/vatSum), параллельны materials
    // по lineNo (= индекс+1). Хранятся отдельно от MaterialDraft чтобы не
    // ломать UI-компонент списка материалов; на финализации мерджатся в items.
    val materialFinancials: List<UpdItemFinancials> = emptyList(),
    // Контрагенты из УПД — нужны при создании приёмки, чтобы веб-портал
    // показал Поставщика/Подрядчика/Получателя в карточке.
    val updSupplierId: String? = null,
    val updContractorId: String? = null,
    val updRecipientMolId: String? = null,
    // Телефон менеджера-автора УПД (тот, кто загрузил через веб /upload-upd*).
    // null если УПД из EDO/mail, загружена до миграции 0039 или у пользователя
    // не указан телефон в админ-разделе. UI рисует иконку звонка в шапке
    // модалки «Материалы» только если phone != null && phone.isNotBlank().
    val managerPhone: String? = null,
    val commentText: String = "",
    val licensePlate: String = "",
    val showPlateError: Boolean = false,
    val manualUpdText: String = "",
    /**
     * Транзит — чекбокс инспектора на 1 этапе. Default false.
     * Сохраняется в Stage1Draft, отправляется в DeliveryRepository.upsert.
     * На веб-портале показывается тегом «🚚 Транзит» в шапке карточки.
     */
    val inTransit: Boolean = false,
    val loadInfo: VehicleLoadInfo? = null,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

/** Финансы одной позиции УПД, сохраняемые рядом с MaterialDraft по lineNo. */
data class UpdItemFinancials(
    val lineNo: Int,
    val price: String?,
    val vatRate: String?,
    val vatSum: String?,
)

/**
 * Сравнение «полезной нагрузки» state'а для distinctUntilChanged автосейва.
 * Исключаем волатильные поля (`isSaving`, `finalized`, `error`, `loadInfo` —
 * computed из УПД, `showPlateError` — UI-флаг), чтобы автосейв не дёргался
 * на каждый сетевой ретрай или Snackbar.
 */
private fun Stage1FormUiState.draftPayloadEquals(other: Stage1FormUiState): Boolean =
    updId == other.updId &&
        documentPhotoPaths == other.documentPhotoPaths &&
        cargoPhotoPaths == other.cargoPhotoPaths &&
        vehicleTypeCode == other.vehicleTypeCode &&
        materials == other.materials &&
        commentText == other.commentText &&
        licensePlate == other.licensePlate &&
        manualUpdText == other.manualUpdText &&
        inTransit == other.inTransit
