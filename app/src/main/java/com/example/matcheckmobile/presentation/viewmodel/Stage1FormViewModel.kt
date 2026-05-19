package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Упрощённая форма «1 Этап» приёмки: фото + тип транспорта (локально) +
 * материалы (по строкам) + комментарий. Сохранение создаёт Delivery со
 * статусом `filled` через [DeliveryRepository.upsert], локальный
 * vehicleTypeCode пишется в delivery_local_meta, фото ставятся в очередь
 * загрузки через [com.example.matcheckmobile.data.repository.PhotoRepository.captureForDelivery].
 *
 * updId передаётся через NavGraph как опциональный аргумент — если есть,
 * приёмка привязывается к выбранной УПД через sourceDocumentIds.
 */
class Stage1FormViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val updId: String? = savedStateHandle[Routes.ARG_UPD_ID]

    private val _state = MutableStateFlow(Stage1FormUiState(updId = updId))
    val state: StateFlow<Stage1FormUiState> = _state.asStateFlow()

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
     * Финализация 1 этапа: создаём Delivery со статусом `filled`, привязываем
     * выбранную УПД (если есть), сохраняем vehicleType локально, ставим фото
     * в очередь загрузки. UI должен перейти на предыдущий экран по флагу
     * [Stage1FormUiState.finalized].
     */
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

                cur.photoPaths.forEach { path ->
                    runCatching {
                        val uri = container.photoStorage.toContentUri(File(path))
                        container.photoRepository.captureForDelivery(
                            deliveryId = deliveryId,
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

data class Stage1FormUiState(
    val updId: String? = null,
    val photoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materialsText: String = "",
    val commentText: String = "",
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)
