package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo
import com.example.matcheckmobile.presentation.navigation.Routes
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                        val withMaterials = if (current.materials.isEmpty())
                            current.copy(materials = drafts)
                        else current
                        withMaterials.copy(loadInfo = gauge)
                    }
                }
            }
        }
        viewModelScope.launch { resolveSiteName() }
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
        if (!isValidPlate(plate)) {
            _state.update { it.copy(showPlateError = true, error = "Неверный формат госномера") }
            return
        }

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

                val plate = cur.licensePlate.trim()
                val userComment = cur.commentText.trim()
                val manualUpd = cur.manualUpdText.trim()
                val combinedComment = buildString {
                    append("Госномер: ").append(plate)
                    if (manualUpd.isNotEmpty() && cur.updId == null) {
                        append('\n').append("УПД: ").append(manualUpd)
                    }
                    if (userComment.isNotEmpty()) {
                        append('\n').append(userComment)
                    }
                }

                val deliveryId = container.deliveryRepository.upsert(
                    DeliveryRepository.UpsertInput(
                        statusCode = "filled",
                        siteId = siteId,
                        comment = combinedComment,
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
    val siteName: String? = null,
    val documentPhotoPaths: List<String> = emptyList(),
    val cargoPhotoPaths: List<String> = emptyList(),
    val vehicleTypeCode: String? = null,
    val materials: List<MaterialDraft> = emptyList(),
    val commentText: String = "",
    val licensePlate: String = "",
    val showPlateError: Boolean = false,
    val manualUpdText: String = "",
    val loadInfo: VehicleLoadInfo? = null,
    val isSaving: Boolean = false,
    val finalized: Boolean = false,
    val error: String? = null,
)

/**
 * Госномер: 1 буква + 3 цифры + 2 буквы + 2-3 цифры региона.
 * Буквы — любые из русского или латинского алфавитов: легковые ходят на 12
 * «общих» буквах (АВЕКМНОРСТУХ), но спецтехника/ведомственные/прицепы/дип-
 * номера используют другие буквы (включая «Г», «Д», «Б» и т.д.). Не блокируем
 * нестандартные комбинации. Пробелы игнорируем, регистр — любой.
 */
private val PLATE_REGEX = Regex(
    "^[А-ЯA-Z]\\d{3}[А-ЯA-Z]{2}\\d{2,3}\$",
)

private fun isValidPlate(input: String): Boolean {
    val cleaned = input.replace(Regex("\\s+"), "").uppercase()
    return PLATE_REGEX.matches(cleaned)
}
