package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.data.repository.Stage1DraftState
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.mergeGroupParty
import com.example.matcheckmobile.domain.model.sortGroupDocs
import com.example.matcheckmobile.media.PhotoSourceInvalidException
import com.example.matcheckmobile.media.photoTakenAtIso
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.sync.PhotoPrepareScheduler
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
    private val initialGroupId: String? = savedStateHandle[Routes.ARG_GROUP_ID]

    /**
     * Идентификатор draft в Room. Заранее сгенерирован: при первом фото
     * автосейв создаст запись с этим id, а навигация по списку «Сегодня»
     * вернёт пользователя сюда же по `draftId`.
     */
    private val draftLocalId: String = initialDraftId ?: UUID.randomUUID().toString()

    private val _state = MutableStateFlow(
        Stage1FormUiState(updId = updId, groupId = initialGroupId),
    )
    val state: StateFlow<Stage1FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restoreDraftOrInitDefaults() }
        viewModelScope.launch { resolveSiteName() }
        viewModelScope.launch { observeAutoSave() }
    }

    /**
     * Восстановление draft. Источники пробуются ПО ОЧЕРЕДИ, а не
     * взаимоисключающе: черновик, начатый до появления группировки, лежит с
     * groupId = null, и поиск только по группе его бы не нашёл — форма завела бы
     * второй черновик, а фото остались бы в осиротевшей записи.
     *
     * 1. По переданному `draftId` (открыт через «Сегодня» либо продолжение начатого).
     * 2. По `groupId` — групповой черновик машины.
     * 3. По `updId` — черновик прошлой сессии либо legacy-запись по якорю.
     * 4. Иначе — обычный init с предзаполнением позициями из документов.
     */
    private suspend fun restoreDraftOrInitDefaults() {
        val repo = container.stage1DraftRepository
        val restored = (
            initialDraftId?.let { repo.findById(it) }
                ?: initialGroupId?.takeIf(String::isNotBlank)?.let { repo.findByGroupId(it) }
                ?: updId?.takeIf(String::isNotBlank)?.let { repo.findByUpdId(it) }
            )?.let { repo.toState(it) }

        if (restored != null) {
            _state.update {
                it.copy(
                    updId = restored.updId,
                    // Группу берём из ВОССТАНОВЛЕННОГО черновика, а не из
                    // аргумента маршрута: при открытии по draftId аргумента нет,
                    // и без этого групповой черновик после свернуть/открыть
                    // превратился бы в приёмку без УПД.
                    groupId = restored.groupId ?: it.groupId,
                    groupDocIds = restored.loadedDocIds,
                    loadedGroupRevision = restored.loadedGroupRevision,
                    documentPhotoPaths = restored.documentPhotoPaths,
                    cargoPhotoPaths = restored.cargoPhotoPaths,
                    vehicleTypeCode = restored.vehicleTypeCode,
                    materials = restored.materials,
                    commentText = restored.commentText,
                    licensePlate = restored.licensePlate,
                    manualUpdText = restored.manualUpdText,
                    inTransit = restored.inTransit,
                    isAssets = restored.isAssets,
                )
            }
        }

        // Состав машины определяет портал, а не инспектор: черновик, начатый до
        // появления группировки, молча принимает группу своего якорного
        // документа. Раньше он оставался одиночным, и остальные документы
        // машины были недоступны — список скрывает всю машину, как только
        // привязан любой её документ (см. filterUpdDocsForSite).
        val docs = resolveGroupDocs(
            groupId = _state.value.groupId,
            anchorUpdId = _state.value.updId ?: updId,
        )
        if (docs.isEmpty()) return

        // УПД-метаданные (контрагенты, финансы по позициям) подтягиваем всегда —
        // даже когда восстановили draft. Materials заполняем из документов
        // только если draft пустой/отсутствует, чтобы не перетереть
        // пользовательский ввод.
        preloadFromSourceDocuments(docs, fillMaterials = restored == null)

        if (restored == null) return

        // Расширение безопасно ровно потому, что материалы добавленных
        // документов дописываются здесь же: иначе finalize привязал бы документ,
        // позиций которого инспектор не видел. Слияние сохраняет его правки.
        val adopted = docs.map { it.id }.toSet()
        if (restored.loadedDocIds.toSet() == adopted) return

        val merged = mergeGroupMaterials(_state.value.materials, loadDocItems(docs))
        val addedDocs = docs.filter { it.id !in restored.loadedDocIds.toSet() }
        _state.update {
            it.copy(
                materials = merged.materials,
                materialFinancials = merged.financials,
                // Уведомление, а не вопрос: решение о составе принято на портале.
                groupAdopted = addedDocs.mapNotNull { d -> d.docNumber }.takeIf { n -> n.isNotEmpty() },
            )
        }
    }

    /**
     * Документы машины в порядке [sortGroupDocs]. Для одиночного документа —
     * список из него одного; для документа, у которого сервер проставил группу,
     * — вся машина, даже если открывали по updId.
     */
    private suspend fun resolveGroupDocs(
        groupId: String?,
        anchorUpdId: String?,
        expandToGroup: Boolean = true,
    ): List<RemoteSourceDocumentEntity> {
        val dao = container.database.remoteSourceDocumentDao()
        val anchor = if (anchorUpdId.isNullOrBlank()) null else {
            runCatching { dao.findById(anchorUpdId) }.getOrNull()
        }
        val effectiveGroupId = if (!expandToGroup) null else {
            groupId?.takeIf(String::isNotBlank) ?: anchor?.groupId
        }
        if (effectiveGroupId != null) {
            val group = runCatching { dao.findByGroupId(effectiveGroupId) }.getOrDefault(emptyList())
            if (group.isNotEmpty()) return sortGroupDocs(group)
        }
        return listOfNotNull(anchor)
    }

    /**
     * Предзаполнение формы документами машины.
     *
     * Позиции всех документов идут подряд в порядке [sortGroupDocs] со СКВОЗНОЙ
     * нумерацией lineNo. Сквозная — обязательное требование: finalizeStage1
     * нумерует позиции как `idx + 1` и по этому же номеру подмешивает финансы
     * из УПД. Разойдись нумерация — цены и НДС уехали бы не к тем строкам.
     */
    private suspend fun preloadFromSourceDocuments(
        docs: List<RemoteSourceDocumentEntity>,
        fillMaterials: Boolean,
    ) {
        val dao = container.database.remoteSourceDocumentDao()
        val anchor = docs.first()
        // Реквизиты — первое непустое по каждому полю, а не «с якоря»:
        // у транспортной накладной нет подрядчика, у части УПД нет
        // грузополучателя. См. mergeGroupParty.
        val party = mergeGroupParty(docs)
        _state.update {
            it.copy(
                updId = anchor.id,
                groupId = anchor.groupId ?: it.groupId,
                groupDocIds = docs.map { d -> d.id },
                loadedGroupRevision = anchor.groupRevision,
                updSupplierId = party.supplierId,
                updContractorId = party.contractorId,
                updRecipientMolId = party.recipientMolId,
                // Телефон менеджера, загрузившего УПД. null для УПД из EDO/mail
                // и для тех, что загружены до серверной миграции 0039 — в этом
                // случае иконка звонка в шапке модалки «Материалы» не рисуется.
                managerPhone = party.createdByUserPhone,
            )
        }

        // Позиции с происхождением: по ним потом видно, чья строка, и можно
        // точечно дописать или убрать позиции при изменении состава машины.
        val items = docs.flatMap { doc ->
            runCatching { dao.findItemsBySource(doc.id) }
                .getOrDefault(emptyList())
                .map { doc.id to it }
        }

        if (items.isEmpty()) return

        // Финансы из УПД сохраняем по lineNo — потом мерджим обратно при
        // финализации, даже если юзер правил qty/name (вьюшка их не показывает,
        // но цена и НДС не меняются от ручной правки количества). Нумерация
        // сквозная по всей машине — см. buildGroupMaterials.
        val built = buildGroupMaterials(items)
        _state.update { current ->
            val withMaterials = if (fillMaterials && current.materials.isEmpty())
                current.copy(materials = built.materials)
            else current
            withMaterials.copy(
                loadInfo = built.loadInfo,
                materialFinancials = built.financials,
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
            // Из snapshot, а не из приватных аргументов навигации: при открытии
            // по groupId или draftId маршрутного updId нет, и черновик машины
            // записался бы как «приёмка без УПД».
            updId = this.updId,
            groupId = groupId,
            loadedDocIds = groupDocIds,
            loadedGroupRevision = loadedGroupRevision,
            documentPhotoPaths = documentPhotoPaths,
            cargoPhotoPaths = cargoPhotoPaths,
            vehicleTypeCode = vehicleTypeCode,
            materials = materials,
            commentText = commentText,
            licensePlate = licensePlate,
            manualUpdText = manualUpdText,
            inTransit = inTransit,
            isAssets = isAssets,
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

    /** Чекбокс «ОС» — см. Stage1FormUiState.isAssets. */
    fun setIsAssets(value: Boolean) {
        _state.update { it.copy(isAssets = value) }
    }

    fun setManualUpd(text: String) {
        _state.update { it.copy(manualUpdText = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Разошёлся ли состав машины с тем, по которому загружалась форма.
     *
     * Сверяем и множество документов, и [RemoteSourceDocumentEntity.groupRevision]:
     * revision растёт ещё и когда внутри документа поменялись реквизиты или
     * позиции — «состав тот же, но суммы другие» сравнением id не ловится.
     *
     * null — расхождения нет (в том числе у приёмок без документов и у
     * черновиков, начатых до появления группировки: сверять там не с чем).
     */
    private suspend fun detectGroupChange(cur: Stage1FormUiState): GroupChange? {
        val groupId = cur.groupId?.takeIf(String::isNotBlank) ?: return null
        if (cur.groupDocIds.isEmpty()) return null
        val actual = runCatching {
            container.database.remoteSourceDocumentDao().findByGroupId(groupId)
        }.getOrNull() ?: return null
        // Пустой ответ — не «все документы исчезли», а скорее всего ещё не
        // доехавшая или почищенная локальная копия. Блокировать финализацию на
        // этом основании нельзя: инспектор стоит у машины.
        if (actual.isEmpty()) return null

        val loaded = cur.groupDocIds.toSet()
        val current = actual.map { it.id }.toSet()
        val added = actual.filter { it.id !in loaded }.map { it.id }
        val removed = cur.groupDocIds.filter { it !in current }
        val revisionChanged = cur.loadedGroupRevision != null &&
            actual.firstNotNullOfOrNull { it.groupRevision }
                ?.let { it != cur.loadedGroupRevision } == true
        return if (added.isEmpty() && removed.isEmpty() && !revisionChanged) null
        else GroupChange(added = added, removed = removed)
    }

    /**
     * «Обновить форму» после расхождения состава.
     *
     * Правка хирургическая — именно ради неё позиции носят происхождение:
     * строки исчезнувших документов убираем, позиции появившихся дописываем в
     * конец, а введённое инспектором (свои строки, правки количества, фото)
     * остаётся нетронутым. Перезалить весь список было бы проще и стёрло бы
     * работу человека.
     */
    fun refreshGroupComposition() {
        viewModelScope.launch {
            applyGroupComposition()
            _state.update { it.copy(groupChanged = null) }
        }
    }

    /**
     * Привести форму к актуальному составу машины. Диалог не трогает — им
     * управляет вызывающий: при финализации уведомление должно остаться на
     * экране уже ПОСЛЕ подтягивания, иначе инспектор не узнает, что изменилось.
     */
    private suspend fun applyGroupComposition() {
        val cur = _state.value
        val docs = resolveGroupDocs(groupId = cur.groupId, anchorUpdId = cur.updId)
        if (docs.isEmpty()) return
        val merged = mergeGroupMaterials(cur.materials, loadDocItems(docs))
        val party = mergeGroupParty(docs)
        _state.update {
            it.copy(
                updId = docs.first().id,
                groupId = docs.first().groupId ?: it.groupId,
                groupDocIds = docs.map { d -> d.id },
                loadedGroupRevision = docs.first().groupRevision,
                materials = merged.materials,
                materialFinancials = merged.financials,
                updSupplierId = party.supplierId,
                updContractorId = party.contractorId,
                updRecipientMolId = party.recipientMolId,
                managerPhone = party.createdByUserPhone,
            )
        }
    }

    /** Позиции всех документов машины из Room, в порядке [sortGroupDocs]. */
    private suspend fun loadDocItems(
        docs: List<RemoteSourceDocumentEntity>,
    ): List<Pair<String, List<RemoteSourceDocumentItemEntity>>> {
        val dao = container.database.remoteSourceDocumentDao()
        return docs.map { doc ->
            doc.id to runCatching { dao.findItemsBySource(doc.id) }.getOrDefault(emptyList())
        }
    }

    /** Уведомление о принятой машине показано — гасим. */
    fun dismissGroupAdopted() {
        _state.update { it.copy(groupAdopted = null) }
    }

    /** Закрыть уведомление о смене состава. */
    fun dismissGroupChange() {
        _state.update { it.copy(groupChanged = null) }
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

        if (cur.cargoPhotoPaths.isEmpty()) {
            _state.update { it.copy(error = "Добавьте фото машины") }
            return
        }

        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            // Состав машины сверяем ПЕРВЫМ делом: если он разошёлся, дальше
            // идти нельзя — приёмка уехала бы с документом, позиций которого
            // инспектор не видел. Проверка до фото, чтобы не жечь IO впустую.
            //
            // Расхождение НЕ спрашивают: состав определяет портал, и выбора
            // «оставить как было» у инспектора нет — такой поставки уже не
            // существует. Поэтому форма подтягивает актуальный состав сама и
            // лишь показывает, что изменилось; финализация ждёт повторного
            // нажатия, чтобы человек увидел итоговый список до отправки.
            val change = detectGroupChange(cur)
            if (change != null) {
                applyGroupComposition()
                _state.update { it.copy(isSaving = false, groupChanged = change) }
                return@launch
            }
            // Кадры проверяем ДО создания приёмки — см. Stage2FormViewModel.
            val photoIntents = try {
                withContext(Dispatchers.IO) {
                    container.photoStorage.intentsFrom(
                        cur.documentPhotoPaths,
                        kind = "document",
                        stage = "before",
                    ) + container.photoStorage.intentsFrom(
                        cur.cargoPhotoPaths,
                        kind = "cargo",
                        stage = "before",
                    )
                }
            } catch (e: PhotoSourceInvalidException) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = "Не сохранились фото: ${e.problems.joinToString("; ")}",
                    )
                }
                return@launch
            }
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
                            // Происхождение обязано уехать вместе с позицией:
                            // при СОЗДАНИИ приёмки сервер берёт его только из
                            // запроса (routes/deliveries.ts, createDelivery) —
                            // переносить ему неоткуда, строк в БД ещё нет.
                            // Без этого все позиции машины из нескольких УПД
                            // легли бы с origin = null, и портал не разложил бы
                            // их по документам.
                            sourceDocumentId = m.sourceDocumentId,
                            sourceDocumentItemId = m.sourceDocumentItemId,
                        )
                    }

                // Весь состав машины, а не один документ. Снимок безопасен:
                // он только что сверен с актуальным состоянием (detectGroupChange).
                // ifEmpty — приёмки, начатые до появления группировки: у них в
                // черновике состава нет, но есть якорный документ.
                val sourceDocIds = cur.groupDocIds.ifEmpty { listOfNotNull(cur.updId) }

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
                        // Empty-draft (приёмка без УПД) обязан передавать
                        // стабильный id: natural-key dedup для него не работает
                        // (DeliveryRepository.upsert), и повтор финализации
                        // после ошибки заводил вторую приёмку. draftLocalId —
                        // уже UUID и переживает перезапуск процесса.
                        id = if (sourceDocIds.isEmpty()) draftLocalId else null,
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
                        isAssets = cur.isAssets,
                        sourceDocumentIds = sourceDocIds,
                        items = items,
                        // Фото ложатся в Room одной транзакцией с мутацией:
                        // приёмка не может уехать без своих кадров. Тяжёлую
                        // подготовку делает PhotoPrepareWorker — локально,
                        // без сети и с ретраями.
                        photos = photoIntents,
                    ),
                )

                container.deliveryRepository.setVehicleType(deliveryId, cur.vehicleTypeCode)

                PhotoPrepareScheduler.requestPrepare(container.appContext)
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
    /** Якорный документ: сам документ либо первый документ машины. */
    val updId: String? = null,
    /**
     * «Машина» — id корневого пакета загрузки. null у приёмки по одиночному
     * документу и у приёмки без документа.
     */
    val groupId: String? = null,
    /**
     * Состав машины, по которому в форму загружены позиции. Пустой список —
     * приёмка без документов; для одиночного документа — список из него одного.
     * Именно он уходит в sourceDocumentIds на финализации.
     */
    val groupDocIds: List<String> = emptyList(),
    /** Версия состава машины на момент загрузки формы. Сверяется на финализации. */
    val loadedGroupRevision: Int? = null,
    /**
     * Состав машины изменился, пока форма была открыта: приехал новый документ
     * или поменялись позиции существующего. Финализация заблокирована до
     * «Обновить форму» — привязать к приёмке документ, позиций которого
     * инспектор не видел, нельзя.
     */
    val groupChanged: GroupChange? = null,
    /**
     * Черновик, начатый до появления группировки, молча принял свою машину:
     * список номеров документов, чьи материалы дописались. Уведомление, а не
     * вопрос — состав поставки определяет портал. null, когда расширения не было.
     */
    val groupAdopted: List<String>? = null,
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
    /**
     * ОС — чекбокс «основные средства» рядом с Транзитом на 1 этапе.
     * Default false. Сохраняется в Stage1Draft, отправляется в
     * DeliveryRepository.upsert. На веб-портале показывается тегом
     * «📦 ОС» в шапке карточки. См. серверную миграцию 0065.
     */
    val isAssets: Boolean = false,
    val loadInfo: VehicleLoadInfo? = null,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

/**
 * Расхождение состава машины между открытием формы и финализацией.
 *
 * [added] — документы, приехавшие в машину позже (обычно допарсился ещё один
 * файл той же загрузки), [removed] — исчезнувшие. Списки могут быть пустыми
 * одновременно: тогда изменились реквизиты или позиции внутри документов, и
 * расхождение поймала только groupRevision.
 */
data class GroupChange(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
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
        groupId == other.groupId &&
        groupDocIds == other.groupDocIds &&
        loadedGroupRevision == other.loadedGroupRevision &&
        documentPhotoPaths == other.documentPhotoPaths &&
        cargoPhotoPaths == other.cargoPhotoPaths &&
        vehicleTypeCode == other.vehicleTypeCode &&
        materials == other.materials &&
        commentText == other.commentText &&
        licensePlate == other.licensePlate &&
        manualUpdText == other.manualUpdText &&
        inTransit == other.inTransit &&
        isAssets == other.isAssets
