package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.presentation.screens.dispatch.DispatchStagesCounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate

/**
 * Счётчики на стартовом экране отгрузки — зеркало [IntakeStagesViewModel].
 *
 * Отличия от приёмки:
 * - источник документов — `direction='outbound'` (накладные/УПД на отгрузку);
 * - drafts — [AppContainer.shipmentStage1DraftRepository];
 * - 2 Этап — shipment.status='shipped' (для приёмки было 'filled').
 *
 * Семантика полей [DispatchStagesCounts] совпадает с приёмкой:
 * - `totalToday` — непривязанные outbound-документы с `expectedDate==today`
 *   без черновика + drafts + отгрузки на 2 Этапе (`shipped`).
 * - `unloadingActive` — drafts + 2-этапные отгрузки.
 * - `overdueUnloading` — подмножество «активных» с моментом «начато»
 *   старше 2 часов: draft.createdAt либо shipment.updatedAt.
 */
class DispatchStagesViewModel(container: AppContainer) : ViewModel() {

    private val attachedIdsFlow = combine(
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
    ) { deliveryJsons, shipmentJsons -> deliveryJsons to shipmentJsons }

    val counts: StateFlow<DispatchStagesCounts> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        attachedIdsFlow,
        container.shipmentStage1DraftRepository.observeAll(),
        container.shipmentRepository.observeByStatuses(STAGE2_STATUSES),
        overdueTicker,
    ) { docs, attached, drafts, stage2Shipments, nowMs ->
        val (deliveryAttachedJsons, shipmentAttachedJsons) = attached
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val today = LocalDate.now().toString()
        val updWithDraft: Set<String> = drafts.mapNotNull { it.updId }.toSet()

        // Всего: непривязанные OUTBOUND-документы на сегодня без draft + drafts
        // + 2-этапные отгрузки. Inbound-документы пропускаем — их обрабатывает
        // Приёмка (там симметричный фильтр direction='inbound').
        val unattachedTodayWithoutDraft = docs.count { d ->
            d.direction == "outbound" &&
                d.id !in attachedIds &&
                d.id !in updWithDraft &&
                d.expectedDate == today
        }
        val totalToday = drafts.size + unattachedTodayWithoutDraft + stage2Shipments.size

        // В отгрузке: drafts (есть фото) + отгрузки на 2 Этапе.
        val unloading = drafts.size + stage2Shipments.size

        // >2ч: draft.createdAt либо shipment.updatedAt старше 2 часов.
        val twoHoursAgoMs = nowMs - 2 * 60 * 60 * 1000L
        val overdueDrafts = drafts.count { it.createdAt in 1 until twoHoursAgoMs }
        val overdueStage2 = stage2Shipments.count { s ->
            val ts = parseIsoMillis(s.updatedAt)
            ts != null && ts < twoHoursAgoMs
        }

        DispatchStagesCounts(
            totalToday = totalToday,
            unloadingActive = unloading,
            overdueUnloading = overdueDrafts + overdueStage2,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DispatchStagesCounts(),
    )

    private companion object {
        /** Совпадает с DispatchStage2ListViewModel.STAGE2_STATUSES. */
        val STAGE2_STATUSES = listOf("shipped")

        /** Тикает раз в 30 секунд — порог «>2 часов» сдвигается без действий пользователя. */
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
