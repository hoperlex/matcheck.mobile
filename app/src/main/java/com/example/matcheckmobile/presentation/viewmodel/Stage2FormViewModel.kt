package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Форма «2 Этап» — подтверждение МОЛ ранее оформленной приёмки. Открывает
 * существующую Delivery по [Routes.ARG_OPERATION_ID], предзаполняет поля и
 * на финализации переводит статус с `filled` на `confirmed_mol`.
 *
 * Локальные фото из state'а (новые) ставятся в очередь загрузки. Старые
 * фото остаются на сервере как есть — отображение в этом этапе пропущено,
 * редактирование старых фото — отдельная задача.
 */
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
        viewModelScope.launch { loadInitial() }
    }

    private suspend fun loadInitial() {
        val delivery = container.deliveryRepository.findById(deliveryId)
        if (delivery == null) {
            _state.update { it.copy(error = "Приёмка не найдена локально") }
            return
        }
        val items = container.deliveryRepository.findItemsByDelivery(deliveryId)
        val materialsText = items.joinToString("\n") { it.nameRaw }
        val vehicleTypeCode = container.deliveryRepository.getVehicleType(deliveryId)
        val sourceDocIds = RemoteMappers.decodeIdList(delivery.sourceDocumentIdsJson)

        _state.update {
            it.copy(
                siteId = delivery.siteId,
                sourceDocumentIds = sourceDocIds,
                materialsText = materialsText,
                commentText = delivery.comment.orEmpty(),
                vehicleTypeCode = vehicleTypeCode,
                loaded = true,
            )
        }
    }

    fun onPhotoTaken(path: String) {
        _state.update { it.copy(photoPaths = it.photoPaths + path) }
    }

    fun removePhoto(path: String) {
        _state.update { it.copy(photoPaths = it.photoPaths - path) }
    }

    fun selectVehicle(code: String?) {
        _state.update { it.copy(vehicleTypeCode = code) }
    }

    fun setMaterials(text: String) {
        _state.update { it.copy(materialsText = text) }
    }

    fun setComment(text: String) {
        _state.update { it.copy(commentText = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Финализация 2 этапа: upsert той же Delivery со статусом `confirmed_mol`,
     * перезапись items из текущего materialsText, сохранение vehicleType,
     * добавление новых фото.
     */
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
                val items = cur.materialsText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapIndexed { idx, line ->
                        DeliveryRepository.ItemInput(
                            nameRaw = line,
                            lineNo = idx + 1,
                        )
                    }

                container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        id = cur.deliveryId,
                        statusCode = "confirmed_mol",
                        siteId = siteId,
                        comment = cur.commentText.ifBlank { null },
                        sourceDocumentIds = cur.sourceDocumentIds,
                        items = items,
                    ),
                )

                container.deliveryRepository.setVehicleType(cur.deliveryId, cur.vehicleTypeCode)

                cur.photoPaths.forEach { path ->
                    runCatching {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = cur.deliveryId,
                            kind = "cargo",
                            sourceUri = uri,
                        )
                    }
                }

                _state.update { it.copy(isSaving = false, finalized = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения") }
            }
        }
    }
}

data class Stage2FormUiState(
    val deliveryId: String,
    val siteId: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    val photoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materialsText: String = "",
    val commentText: String = "",
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
