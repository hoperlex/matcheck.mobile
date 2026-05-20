package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
class Stage1FormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val updId: String? = savedStateHandle[Routes.ARG_UPD_ID]

    private val _state = MutableStateFlow(Stage1FormUiState(updId = updId))
    val state: StateFlow<Stage1FormUiState> = _state.asStateFlow()

    init {
        val id = updId
        if (!id.isNullOrBlank()) {
            viewModelScope.launch {
                val items = runCatching {
                    container.database.remoteSourceDocumentDao().findItemsBySource(id)
                }.getOrDefault(emptyList())
                if (items.isNotEmpty()) {
                    val drafts = items.map { MaterialDraft(name = it.nameRaw, qty = it.qty) }
                    _state.update { current ->
                        if (current.materials.isEmpty()) current.copy(materials = drafts)
                        else current
                    }
                }
            }
        }
    }

    fun onDocumentPhotoTaken(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths + path) }
    }

    fun removeDocumentPhoto(path: String) {
        _state.update { it.copy(documentPhotoPaths = it.documentPhotoPaths - path) }
    }

    fun onCargoPhotoTaken(path: String) {
        _state.update { it.copy(cargoPhotoPaths = it.cargoPhotoPaths + path) }
    }

    fun removeCargoPhoto(path: String) {
        _state.update { it.copy(cargoPhotoPaths = it.cargoPhotoPaths - path) }
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

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun finalizeStage1() {
        val cur = _state.value
        if (cur.isSaving || cur.finalized) return

        val siteId = container.tokenStorage.state.value.siteId
        if (siteId.isNullOrBlank()) {
            _state.update { it.copy(error = "Нет привязки к объекту, переавторизуйтесь") }
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

                val sourceDocIds = cur.updId?.let { listOf(it) } ?: emptyList()

                val deliveryId = container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        statusCode = "filled",
                        siteId = siteId,
                        comment = cur.commentText.ifBlank { null },
                        sourceDocumentIds = sourceDocIds,
                        items = items,
                    ),
                )

                container.deliveryRepository.setVehicleType(deliveryId, cur.vehicleTypeCode)

                cur.documentPhotoPaths.forEach { path ->
                    runCatching {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = deliveryId,
                            kind = "document",
                            sourceUri = uri,
                        )
                    }
                }

                cur.cargoPhotoPaths.forEach { path ->
                    runCatching {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = deliveryId,
                            kind = "cargo",
                            sourceUri = uri,
                        )
                    }
                }

                MatcheckSyncScheduler.requestImmediateSync(container.appContext)

                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }
}

data class Stage1FormUiState(
    val updId: String? = null,
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    val commentText: String = "",
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
