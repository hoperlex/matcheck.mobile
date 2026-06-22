package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
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
 * «Ручной внос» — приёмка без УПД и автотранспорта.
 *
 * Инспектор делает фото (документы + груз), указывает номер УПД текстом,
 * материалы и комментарий — приёмка сразу создаётся в статусе
 * `confirmed_mol`. На веб-портале попадает в «Принятые / Подтверждено МОЛ»
 * с тегом «Без документа» (sourceDocumentIds=[]), confirmedByMolUserId
 * = инспектор (сервер заполняет в createDelivery при status='confirmed_mol').
 *
 * Отличия от Stage1FormViewModel:
 *  - нет vehiclePlate, vehicleTypeCode, inTransit (это не автотранспорт);
 *  - нет загрузки УПД (sourceDocumentIds всегда пуст);
 *  - statusCode на финализе = 'confirmed_mol' (а не 'filled');
 *  - нет draft-персистентности — экран короткий, риск потерять данные
 *    мал; в v2 при жалобах добавим.
 */
class ManualEntryFormViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualEntryFormUiState())
    val state: StateFlow<ManualEntryFormUiState> = _state.asStateFlow()

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

        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
            return
        }

        // Минимальная валидация: хотя бы одно фото, чтобы не плодить пустые
        // «приёмки». Согласовано с тем, как работает 1 Этап «Автотранспорт».
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

                val deliveryId = container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        // Сразу confirmed_mol: сервер в createDelivery видит
                        // status='confirmed_mol' и автоматически заполняет
                        // confirmedByMolUserId/At из inspectorId.
                        statusCode = "confirmed_mol",
                        siteId = siteId,
                        // Все «автотранспортные» поля null — это ручной внос.
                        supplierId = null,
                        contractorId = null,
                        recipientMolId = null,
                        vehiclePlate = null,
                        arrivedAt = java.time.Instant.now().toString(),
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
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
