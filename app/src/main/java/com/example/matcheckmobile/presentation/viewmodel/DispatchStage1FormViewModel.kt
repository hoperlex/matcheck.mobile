package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.ShipmentStage1DraftState
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
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
    private val initialGroupId: String? = savedStateHandle[Routes.ARG_GROUP_ID]
    private val draftLocalId: String = initialDraftId ?: UUID.randomUUID().toString()

    private val _state = MutableStateFlow(
        DispatchStage1FormUiState(updId = updId, groupId = initialGroupId),
    )
    val state: StateFlow<DispatchStage1FormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restoreDraftOrInitDefaults() }
        viewModelScope.launch { resolveSiteName() }
        viewModelScope.launch { observeAutoSave() }
    }

    /** Зеркало `Stage1FormViewModel.restoreDraftOrInitDefaults` — см. KDoc там. */
    private suspend fun restoreDraftOrInitDefaults() {
        val repo = container.shipmentStage1DraftRepository
        val restored = (
            initialDraftId?.let { repo.findById(it) }
                ?: initialGroupId?.takeIf(String::isNotBlank)?.let { repo.findByGroupId(it) }
                ?: updId?.takeIf(String::isNotBlank)?.let { repo.findByUpdId(it) }
            )?.let { repo.toState(it) }

        if (restored != null) {
            _state.update {
                it.copy(
                    updId = restored.updId,
                    // Группа из восстановленного черновика — см. Stage1FormViewModel.
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
                    shipmentPurpose = restored.shipmentPurpose,
                    inTransit = restored.inTransit,
                    isAssets = restored.isAssets,
                )
            }
        }

        // Состав машины определяет портал — legacy-черновик молча принимает
        // группу своего якоря, а материалы добавленных документов дописываются
        // ниже. См. Stage1FormViewModel.
        val docs = resolveGroupDocs(
            groupId = _state.value.groupId,
            anchorUpdId = _state.value.updId ?: updId,
        )
        if (docs.isEmpty()) return

        preloadFromSourceDocuments(docs, fillMaterials = restored == null)

        if (restored == null) return
        val adopted = docs.map { it.id }.toSet()
        if (restored.loadedDocIds.toSet() == adopted) return

        val merged = mergeGroupMaterials(_state.value.materials, loadDocItems(docs))
        val addedDocs = docs.filter { it.id !in restored.loadedDocIds.toSet() }
        _state.update {
            it.copy(
                materials = merged.materials,
                materialFinancials = merged.financials,
                groupAdopted = addedDocs.mapNotNull { d -> d.docNumber }.takeIf { n -> n.isNotEmpty() },
            )
        }
    }

    /** Зеркало `Stage1FormViewModel.loadDocItems`. */
    private suspend fun loadDocItems(
        docs: List<RemoteSourceDocumentEntity>,
    ): List<Pair<String, List<RemoteSourceDocumentItemEntity>>> {
        val dao = container.database.remoteSourceDocumentDao()
        return docs.map { doc ->
            doc.id to runCatching { dao.findItemsBySource(doc.id) }.getOrDefault(emptyList())
        }
    }

    /** Зеркало `Stage1FormViewModel.resolveGroupDocs`. */
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

    /** Зеркало `Stage1FormViewModel.preloadFromSourceDocuments`. */
    private suspend fun preloadFromSourceDocuments(
        docs: List<RemoteSourceDocumentEntity>,
        fillMaterials: Boolean,
    ) {
        val dao = container.database.remoteSourceDocumentDao()
        val anchor = docs.first()
        val party = mergeGroupParty(docs)
        _state.update {
            it.copy(
                updId = anchor.id,
                groupId = anchor.groupId ?: it.groupId,
                groupDocIds = docs.map { d -> d.id },
                loadedGroupRevision = anchor.groupRevision,
                updSupplierId = party.supplierId,
                // Для отгрузки получателем чаще выступает recipientId УПД
                // (контрагент-получатель) либо МОЛ-получатель. Если МОЛ
                // указан — он перебивает контрагента (XOR на сервере).
                updReceiverCounterpartyId = docs.firstNotNullOfOrNull { d -> d.recipientId },
                updReceiverMolId = party.recipientMolId,
            )
        }

        val items = docs.flatMap { doc ->
            runCatching { dao.findItemsBySource(doc.id) }
                .getOrDefault(emptyList())
                .map { doc.id to it }
        }

        if (items.isEmpty()) return

        // Сквозная нумерация lineNo и происхождение позиций — см. buildGroupMaterials.
        val built = buildGroupMaterials(items)
        _state.update { current ->
            val withMaterials = if (fillMaterials && current.materials.isEmpty())
                current.copy(materials = built.materials)
            else current
            withMaterials.copy(loadInfo = built.loadInfo, materialFinancials = built.financials)
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
            // Из snapshot, а не из аргументов навигации — см. Stage1FormViewModel.
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
            shipmentPurpose = shipmentPurpose,
            inTransit = inTransit,
            isAssets = isAssets,
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
    /** Чекбокс «ОС» — см. DispatchStage1FormUiState.isAssets. */
    fun setIsAssets(value: Boolean) { _state.update { it.copy(isAssets = value) } }
    fun dismissError() { _state.update { it.copy(error = null) } }

    /** Зеркало `Stage1FormViewModel.detectGroupChange`. */
    private suspend fun detectGroupChange(cur: DispatchStage1FormUiState): GroupChange? {
        val groupId = cur.groupId?.takeIf(String::isNotBlank) ?: return null
        if (cur.groupDocIds.isEmpty()) return null
        val actual = runCatching {
            container.database.remoteSourceDocumentDao().findByGroupId(groupId)
        }.getOrNull() ?: return null
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

    /** Зеркало `Stage1FormViewModel.refreshGroupComposition`. */
    fun refreshGroupComposition() {
        viewModelScope.launch {
            applyGroupComposition()
            _state.update { it.copy(groupChanged = null) }
        }
    }

    /** Зеркало `Stage1FormViewModel.applyGroupComposition` — см. KDoc там. */
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
                updReceiverCounterpartyId = docs.firstNotNullOfOrNull { d -> d.recipientId },
                updReceiverMolId = party.recipientMolId,
            )
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
        // Regex-валидация формата убрана: те же резоны что в Stage1FormViewModel
        // (спецтехника / прицепы / ведомственные номера). Достаточно непустоты.
        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        if (cur.cargoPhotoPaths.isEmpty()) {
            _state.update { it.copy(error = "Добавьте фото машины") }
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
            // Состав машины сверяем первым делом — см. Stage1FormViewModel.
            // Расхождение состава не спрашивают — подтягиваем сами и лишь
            // показываем, что изменилось. См. Stage1FormViewModel.
            val change = detectGroupChange(cur)
            if (change != null) {
                applyGroupComposition()
                _state.update { it.copy(isSaving = false, groupChanged = change) }
                return@launch
            }
            // Кадры проверяем ДО создания отгрузки — см. Stage2FormViewModel.
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
                            // Зеркало Stage1FormViewModel — см. комментарий там.
                            sourceDocumentId = m.sourceDocumentId,
                            sourceDocumentItemId = m.sourceDocumentItemId,
                        )
                    }

                // Весь состав машины — см. Stage1FormViewModel.
                val sourceDocIds = cur.groupDocIds.ifEmpty { listOfNotNull(cur.updId) }
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
                        isAssets = cur.isAssets,
                        sourceDocumentIds = sourceDocIds,
                        items = items,
                        // Все фото 1-го этапа отгрузки помечаем stage='before'
                        // (зеркало приёмки). Stage2 будет ставить 'after' —
                        // тогда веб-портал разделит «1 Этап (N) / 2 Этап (M)»
                        // в шапке отгрузки. Строки создаются той же транзакцией,
                        // что и мутация.
                        photos = photoIntents,
                    ),
                )

                // Зеркало Stage1FormViewModel — сохраняем выбранный тип
                // транспорта в локальной таблице shipment_local_meta, чтобы
                // на 2 Этапе восстановить выбор (в shipment DTO такого поля
                // нет, иначе терялось бы при /sync).
                container.shipmentRepository.setVehicleType(shipmentId, cur.vehicleTypeCode)

                PhotoPrepareScheduler.requestPrepare(container.appContext)
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
    /** Якорный документ, см. [Stage1FormUiState.updId]. */
    val updId: String? = null,
    /** «Машина», см. [Stage1FormUiState.groupId]. */
    val groupId: String? = null,
    /** Состав машины, по которому загружены позиции. См. [Stage1FormUiState.groupDocIds]. */
    val groupDocIds: List<String> = emptyList(),
    /** Версия состава на момент загрузки формы. */
    val loadedGroupRevision: Int? = null,
    /** Состав машины разошёлся, см. [Stage1FormUiState.groupChanged]. */
    val groupChanged: GroupChange? = null,
    /** Зеркало [Stage1FormUiState.groupAdopted]. */
    val groupAdopted: List<String>? = null,
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
    /**
     * ОС — чекбокс «основные средства» рядом с Транзитом. Default false.
     * Сохраняется в shipment_stage1_draft, отправляется в
     * ShipmentRepository.upsert. На веб-портале — тег «📦 ОС».
     * См. серверную миграцию 0065.
     */
    val isAssets: Boolean = false,
    val loadInfo: VehicleLoadInfo? = null,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

private fun DispatchStage1FormUiState.draftPayloadEquals(other: DispatchStage1FormUiState): Boolean =
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
        shipmentPurpose == other.shipmentPurpose &&
        inTransit == other.inTransit &&
        isAssets == other.isAssets

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
