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
import java.time.LocalDate

/**
 * Счётчики на кнопке «1 Этап» стартового экрана приёмки.
 *
 * - `totalToday` — «Всего»: УПД с `expectedDate == сегодня`, ещё не
 *   привязанные к Delivery/Shipment. Совпадает со счётчиком «Сегодня»
 *   во вкладках [IntakeUpdSelectViewModel] для server-snapshot записей.
 * - `unloadingActive` — «В разгрузке»: все [stage1_drafts]. Draft
 *   создаётся ровно при первом фото, что и означает «Начато».
 * - `overdueUnloading` — «Разгрузка >2ч»: drafts с `updatedAt` старше
 *   двух часов. Тикер каждые 30с переоценивает порог.
 */
data class IntakeStagesCounts(
    val totalToday: Int = 0,
    val unloadingActive: Int = 0,
    val overdueUnloading: Int = 0,
)

class IntakeStagesViewModel(container: AppContainer) : ViewModel() {

    val counts: StateFlow<IntakeStagesCounts> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
        container.stage1DraftRepository.observeAll(),
        overdueTicker,
    ) { docs, deliveryAttachedJsons, shipmentAttachedJsons, drafts, nowMs ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val today = LocalDate.now().toString()
        val totalToday = docs.count { it.id !in attachedIds && it.expectedDate == today }
        val unloading = drafts.size
        val twoHoursAgoMs = nowMs - 2 * 60 * 60 * 1000L
        val overdue = drafts.count { it.updatedAt < twoHoursAgoMs }
        IntakeStagesCounts(
            totalToday = totalToday,
            unloadingActive = unloading,
            overdueUnloading = overdue,
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
    }
}
