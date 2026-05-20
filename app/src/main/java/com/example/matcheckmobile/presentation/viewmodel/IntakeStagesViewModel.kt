package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Счётчики «активных УПД» для стартового экрана приёмки.
 *
 * - Stage 1: ожидаемые УПД с веб-портала, ещё не привязанные к Delivery/Shipment.
 *   Фильтр аналогичен [IntakeUpdSelectViewModel] — берём `remote_source_documents`
 *   и исключаем id, привязанные локально через `remote_deliveries` и
 *   `remote_shipments` (сервер не присылает их в дельте после привязки).
 * - Stage 2: приёмки в статусе `filled` («Оформлена»), ожидающие подтверждения МОЛ.
 */
data class IntakeStagesCounts(
    val stage1Active: Int = 0,
    val stage2Active: Int = 0,
)

class IntakeStagesViewModel(container: AppContainer) : ViewModel() {

    val counts: StateFlow<IntakeStagesCounts> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
        container.deliveryRepository.observeByStatus("filled").map { it.size },
    ) { docs, deliveryAttachedJsons, shipmentAttachedJsons, stage2Count ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val stage1Count = docs.count { it.id !in attachedIds }
        IntakeStagesCounts(stage1Active = stage1Count, stage2Active = stage2Count)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeStagesCounts(),
    )
}
