package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Ручной вынос» — отгрузка без УПД и автотранспорта (зеркало
 * [ManualEntryFormViewModel] для shipment'ов).
 *
 * Инспектор делает фото (документы + груз), указывает номер УПД текстом,
 * материалы и комментарий — отгрузка сразу создаётся в статусе
 * `confirmed_mol`. На веб-портале попадает в «Отгрузка / Принятые /
 * Подтверждено МОЛ» с тегом «Без документа» (sourceDocumentIds=[]),
 * confirmedByMolUserId = инспектор (сервер заполняет в createShipment при
 * status='confirmed_mol' — см. routes/shipments.ts isDirectConfirm).
 *
 * `kind = 'contractor'` — самый универсальный, без получателя (validateKindLinks
 * допускает empty-draft contractor без receiver). Менеджер на портале при
 * необходимости дозaпoлнит получателя.
 *
 * Отличия от DispatchStage1FormViewModel:
 *  - нет vehiclePlate, purpose, inTransit (это не автотранспорт);
 *  - нет загрузки УПД (sourceDocumentIds всегда пуст);
 *  - statusCode на финализе = 'confirmed_mol' (а не 'shipped');
 *  - нет draft-персистентности — экран короткий, риск потерять данные мал.
 */
class ManualDispatchFormViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualDispatchFormUiState())
    val state: StateFlow<ManualDispatchFormUiState> = _state.asStateFlow()

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

    /** «Тип отгрузки» — одно из [SHIPMENT_PURPOSE_OPTIONS]. null = очистка. */
    fun setShipmentPurpose(value: String?) {
        _state.update { it.copy(shipmentPurpose = value) }
    }

    /** ОС — чекбокс «основные средства» на ручном выносе. */
    fun setIsAssets(value: Boolean) {
        _state.update { it.copy(isAssets = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun finalize() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized) return

        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        // Минимальная валидация: хотя бы одно фото, чтобы не плодить пустые
        // «отгрузки». Согласовано с тем, как работает 1 Этап «Автотранспорт».
        if (cur.documentPhotoPaths.isEmpty() && cur.cargoPhotoPaths.isEmpty()) {
            _state.update { it.copy(error = "Добавьте хотя бы одно фото") }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val items = cur.materials
                    .filter { it.name.isNotBlank() || it.qty.isNotBlank() }
                    .mapIndexed { idx, m ->
                        ShipmentRepository.ItemInput(
                            nameRaw = m.name.trim().ifEmpty { "—" },
                            qtyActual = m.qty.trim().ifEmpty { null },
                            unit = m.unit.ifEmpty { "шт" },
                            lineNo = idx + 1,
                            price = null,
                            vatRate = null,
                            vatSum = null,
                        )
                    }

                val userComment = cur.commentText.trim()
                val manualUpd = cur.manualUpdText.trim()
                val commentParts = buildList {
                    add("Ручной вынос")
                    if (userComment.isNotEmpty()) add("Комментарий: \"$userComment\"")
                    if (manualUpd.isNotEmpty()) add("Примечание: $manualUpd")
                }
                val commentForServer = commentParts.joinToString("\n")

                val shipmentId = container.shipmentRepository.upsert(
                    ShipmentRepository.UpsertInput(
                        // Сразу confirmed_mol: сервер в createShipment видит
                        // status='confirmed_mol' и автоматически заполняет
                        // confirmedByMolUserId/At из inspectorId (см. серверный
                        // isDirectConfirm в createShipment).
                        statusCode = "confirmed_mol",
                        // kind='contractor' — наиболее универсальный; для
                        // empty-draft (sourceDocumentIds=[]) validateKindLinks
                        // допускает отсутствие получателя.
                        kind = "contractor",
                        siteId = siteId,
                        receiverCounterpartyId = null,
                        receiverMolId = null,
                        destSiteId = null,
                        // Госномер на ручном выносе не вводится (это не автотранспорт) —
                        // всегда null. Если бизнес захочет вернуть поле, добавлять
                        // в UI и в state симметрично DispatchStage1FormViewModel.
                        vehiclePlate = null,
                        driverName = null,
                        shippedAt = java.time.Instant.now().toString(),
                        comment = commentForServer,
                        // Тип отгрузки — отдельным полем shipments.purpose
                        // (миграция 0049). В comment не дублируем.
                        purpose = cur.shipmentPurpose?.takeIf { it.isNotBlank() },
                        inTransit = false,
                        // ОС — флаг «основные средства». На сервере — shipments.is_assets
                        // (миграция 0065).
                        isAssets = cur.isAssets,
                        sourceDocumentIds = emptyList(),
                        items = items,
                    ),
                )

                val photoErrors = mutableListOf<String>()
                // Фото с stage='before' — на веб-портале попадут в секцию
                // «1 Этап». «2 Этап» останется пустым (для ручного вноса
                // второго этапа нет — инспектор уже подтвердил собой).
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
                        android.util.Log.e("ManualDispatch", "document photo failed: $path", t)
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
                        android.util.Log.e("ManualDispatch", "cargo photo failed: $path", t)
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
                    _state.update { it.copy(isSaving = false, finalized = true) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ManualDispatch", "finalize failed", t)
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

data class ManualDispatchFormUiState(
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val manualUpdText: String = "",
    val materials: List<MaterialDraft> = emptyList(),
    val commentText: String = "",
    /**
     * «Тип отгрузки» — одно из [SHIPMENT_PURPOSE_OPTIONS]. На finalize
     * уходит в ShipmentRepository.UpsertInput.purpose (поле shipments.purpose,
     * миграция 0049).
     */
    val shipmentPurpose: String? = null,
    /** ОС — чекбокс «основные средства». На finalize → ShipmentRepository.UpsertInput.isAssets. */
    val isAssets: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
