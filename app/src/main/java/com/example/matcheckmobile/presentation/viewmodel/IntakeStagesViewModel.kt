package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * Счётчики «активных УПД» для стартового экрана приёмки.
 *
 * - Stage 1: ожидаемые УПД с веб-портала, ещё не привязанные к Delivery/Shipment.
 *   Фильтр аналогичен [IntakeUpdSelectViewModel] — берём `remote_source_documents`
 *   и исключаем id, привязанные локально через `remote_deliveries` и
 *   `remote_shipments` (сервер не присылает их в дельте после привязки).
 * - Stage 2: приёмки в статусах `filled` («Оформлена») и `no_document` («Без
 *   документа»), ожидающие подтверждения МОЛ. Список совпадает со [Stage2ListViewModel].
 * - overdueStage2: те же приёмки 2-го Этапа, у которых [updatedAt] (момент
 *   перехода в filled/no_document на финализации 1-го Этапа) старше 2 часов.
 */
data class IntakeStagesCounts(
    val total: Int = 0,
    val stage1Active: Int = 0,
    val stage2Active: Int = 0,
    val overdueStage2: Int = 0,
)

class IntakeStagesViewModel(container: AppContainer) : ViewModel() {

    val counts: StateFlow<IntakeStagesCounts> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
        container.deliveryRepository.observeByStatuses(listOf("filled", "no_document")),
        overdueTicker,
    ) { docs, deliveryAttachedJsons, shipmentAttachedJsons, stage2Deliveries, nowMs ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val stage1Count = docs.count { it.id !in attachedIds }
        val stage2Count = stage2Deliveries.size
        val twoHoursAgoMs = nowMs - 2 * 60 * 60 * 1000L
        val overdueCount = stage2Deliveries.count { d ->
            val ts = parseIsoMillis(d.updatedAt)
            ts != null && ts < twoHoursAgoMs
        }
        IntakeStagesCounts(
            total = stage1Count + stage2Count,
            stage1Active = stage1Count,
            stage2Active = stage2Count,
            overdueStage2 = overdueCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeStagesCounts(),
    )

    private companion object {
        /** Тикает раз в 30 секунд, чтобы счётчик «>2 часов» переезжал по мере старения. */
        val overdueTicker = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(30_000L)
            }
        }

        fun parseIsoMillis(s: String?): Long? = try {
            if (s.isNullOrBlank()) null else Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
