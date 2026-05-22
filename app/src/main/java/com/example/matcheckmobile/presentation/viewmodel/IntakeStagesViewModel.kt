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
 * - `totalToday` — «Всего»: то же множество, что отображается во вкладке
 *   «Сегодня» 1 Этапа: empty-drafts + УПД-drafts + неприкреплённые УПД с
 *   `expectedDate == today` без draft (без двойного счёта).
 * - `unloadingActive` — «В разгрузке»: все [stage1_drafts]. Draft существует
 *   ровно тогда, когда есть хотя бы одно фото — это и есть «Начато».
 * - `overdueUnloading` — «Разгрузка >2ч»: drafts с `createdAt` (момент «Начато»,
 *   первое фото) старше двух часов. Тикер каждые 30с двигает порог.
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
        val updWithDraft: Set<String> = drafts.mapNotNull { it.updId }.toSet()

        // Всего = эквивалент списка «Сегодня» на 1 Этапе:
        //   (empty + УПД-drafts) + неприкреплённые УПД на сегодня без draft.
        val unattachedTodayWithoutDraft = docs.count { d ->
            d.id !in attachedIds &&
                d.id !in updWithDraft &&
                d.expectedDate == today
        }
        val totalToday = drafts.size + unattachedTodayWithoutDraft

        // В разгрузке: все drafts (== «Начато», есть хотя бы одно фото).
        val unloading = drafts.size

        // >2ч: считаем по createdAt (момент первого фото = момент старта),
        // НЕ updatedAt — иначе любой ввод текста сбрасывает таймер.
        val twoHoursAgoMs = nowMs - 2 * 60 * 60 * 1000L
        val overdue = drafts.count { it.createdAt in 1 until twoHoursAgoMs }

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
