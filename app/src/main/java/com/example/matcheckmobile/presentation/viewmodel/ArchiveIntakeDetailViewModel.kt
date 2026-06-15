package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Детальный экран архивной приёмки: разбивает фото на 2 этапа и 2 категории
 * (документы / груз+машина), плюс показывает время заезда и выезда.
 *
 * Этап определяется по полю `stage` ('before' = 1 Этап, 'after' = 2 Этап,
 * см. [com.example.matcheckmobile.data.repository.PhotoRepository.captureForDelivery]).
 * Категория — по полю `kind`: 'document' — документы; 'cargo'/'vehicle'/'other'
 * — фото груза/машины. На UI рисуем под подписями «Документ» и «Груз/машина».
 */
class ArchiveIntakeDetailViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deliveryId: String =
        savedStateHandle[Routes.ARG_DELIVERY_ID]
            ?: error("ArchiveIntakeDetailViewModel: deliveryId required")

    private val _state = MutableStateFlow(ArchiveIntakeDetailUiState(deliveryId = deliveryId))
    val state: StateFlow<ArchiveIntakeDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            load()
        }
    }

    private suspend fun load() {
        val delivery = container.deliveryRepository.findById(deliveryId)
        if (delivery == null) {
            _state.update { it.copy(error = "Приёмка не найдена локально", loaded = true) }
            return
        }
        val photos = container.database.remoteDeliveryDao().findPhotosByDelivery(deliveryId)
        val before = photos.filter { it.stage == "before" }
        val after = photos.filter { it.stage == "after" }
        val arrivedAtMs = parseInstantToMs(delivery.arrivedAt)
        val confirmedAtMs = parseInstantToMs(delivery.confirmedByMolAt)

        _state.update {
            it.copy(
                loaded = true,
                vehiclePlate = delivery.vehiclePlate?.takeIf { p -> p.isNotBlank() },
                arrivedAtMs = arrivedAtMs,
                confirmedAtMs = confirmedAtMs,
                stage1DocumentPhotos = before.filter { p -> p.kind == "document" },
                stage1VehiclePhotos = before.filter { p -> p.kind != "document" },
                stage2DocumentPhotos = after.filter { p -> p.kind == "document" },
                stage2VehiclePhotos = after.filter { p -> p.kind != "document" },
            )
        }
    }

    private fun parseInstantToMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }
}

data class ArchiveIntakeDetailUiState(
    val deliveryId: String,
    val loaded: Boolean = false,
    val error: String? = null,
    val vehiclePlate: String? = null,
    val arrivedAtMs: Long? = null,
    val confirmedAtMs: Long? = null,
    val stage1DocumentPhotos: List<RemoteDeliveryPhotoEntity> = emptyList(),
    val stage1VehiclePhotos: List<RemoteDeliveryPhotoEntity> = emptyList(),
    val stage2DocumentPhotos: List<RemoteDeliveryPhotoEntity> = emptyList(),
    val stage2VehiclePhotos: List<RemoteDeliveryPhotoEntity> = emptyList(),
)
