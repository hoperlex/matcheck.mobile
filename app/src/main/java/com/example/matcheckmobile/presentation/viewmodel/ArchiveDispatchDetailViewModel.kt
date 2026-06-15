package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Зеркало [ArchiveIntakeDetailViewModel] для отгрузки: разбивает фото
 * по этапу (`stage='before'` → 1 Этап, `'after'` → 2 Этап) и по категории
 * (`kind='document'` → «Документ», иначе → «Груз/машина»).
 *
 * Времена этапов: 1 Этап → `shippedAt` (момент «Завершить 1 Этап» в
 * `DispatchStage1FormViewModel.finalizeStage1`), 2 Этап → `confirmedByMolAt`.
 */
class ArchiveDispatchDetailViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val shipmentId: String =
        savedStateHandle[Routes.ARG_SHIPMENT_ID]
            ?: error("ArchiveDispatchDetailViewModel: shipmentId required")

    private val _state = MutableStateFlow(ArchiveDispatchDetailUiState(shipmentId = shipmentId))
    val state: StateFlow<ArchiveDispatchDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val shipment = container.shipmentRepository.findById(shipmentId)
        if (shipment == null) {
            _state.update { it.copy(error = "Отгрузка не найдена локально", loaded = true) }
            return
        }
        val photos = container.database.remoteShipmentDao().findPhotosByShipment(shipmentId)
        val before = photos.filter { it.stage == "before" }
        val after = photos.filter { it.stage == "after" }
        val shippedAtMs = parseInstantToMs(shipment.shippedAt)
        val confirmedAtMs = parseInstantToMs(shipment.confirmedByMolAt)

        _state.update {
            it.copy(
                loaded = true,
                vehiclePlate = shipment.vehiclePlate?.takeIf { p -> p.isNotBlank() },
                shippedAtMs = shippedAtMs,
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

data class ArchiveDispatchDetailUiState(
    val shipmentId: String,
    val loaded: Boolean = false,
    val error: String? = null,
    val vehiclePlate: String? = null,
    val shippedAtMs: Long? = null,
    val confirmedAtMs: Long? = null,
    val stage1DocumentPhotos: List<RemoteShipmentPhotoEntity> = emptyList(),
    val stage1VehiclePhotos: List<RemoteShipmentPhotoEntity> = emptyList(),
    val stage2DocumentPhotos: List<RemoteShipmentPhotoEntity> = emptyList(),
    val stage2VehiclePhotos: List<RemoteShipmentPhotoEntity> = emptyList(),
)
