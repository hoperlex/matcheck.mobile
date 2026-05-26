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
import java.time.LocalDate

/**
 * Счётчики на стартовом экране приёмки.
 *
 * - `totalToday` — «Всего»: непривязанные УПД с `expectedDate == today` без
 *   draft + все черновики 1 Этапа + приёмки на 2 Этапе (`filled`). Без двойного
 *   счёта: УПД, у которой есть draft, попадает только в «drafts.size».
 * - `unloadingActive` — «В разгрузке»: drafts (фактически — там есть фото,
 *   draft создаётся только при первом снимке) + приёмки на 2 Этапе.
 * - `overdueUnloading` — «Разгрузка >2ч»: подмножество «В разгрузке», у
 *   которого момент «начато» старше двух часов:
 *   — для draft это `createdAt` (первое фото),
 *   — для filled-приёмки это `updatedAt` (момент «Завершить 1 Этап»).
 *   Тикер раз в 30с двигает порог.
 */
data class IntakeStagesCounts(
    val totalToday: Int = 0,
    val unloadingActive: Int = 0,
    val overdueUnloading: Int = 0,
)

class IntakeStagesViewModel(container: AppContainer) : ViewModel() {

    // Сворачиваем оба «attached»-потока в один Pair, чтобы остаться в пределах
    // 5-арного combine при расширении состава источников.
    private val attachedIdsFlow = combine(
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
    ) { deliveryJsons, shipmentJsons -> deliveryJsons to shipmentJsons }

    val counts: StateFlow<IntakeStagesCounts> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        attachedIdsFlow,
        container.stage1DraftRepository.observeAll(),
        container.deliveryRepository.observeByStatuses(STAGE2_STATUSES),
        overdueTicker,
    ) { docs, attached, drafts, stage2Deliveries, nowMs ->
        val (deliveryAttachedJsons, shipmentAttachedJsons) = attached
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val today = LocalDate.now().toString()
        val updWithDraft: Set<String> = drafts.mapNotNull { it.updId }.toSet()

        // Всего: непривязанные УПД на сегодня без draft + drafts + filled-приёмки.
        val unattachedTodayWithoutDraft = docs.count { d ->
            d.id !in attachedIds &&
                d.id !in updWithDraft &&
                d.expectedDate == today
        }
        val totalToday = drafts.size + unattachedTodayWithoutDraft + stage2Deliveries.size

        // В разгрузке: drafts (== есть фото) + приёмки на 2 Этапе.
        val unloading = drafts.size + stage2Deliveries.size

        // >2ч: для draft — createdAt (первое фото). Для filled-приёмки —
        // updatedAt (момент перехода в filled при «Завершить 1 Этап»).
        // updatedAt сервер возвращает как ISO, локально пишем тоже ISO в
        // DeliveryRepository.upsert — нужно распарсить в epoch-мс.
        val twoHoursAgoMs = nowMs - 2 * 60 * 60 * 1000L
        val overdueDrafts = drafts.count { it.createdAt in 1 until twoHoursAgoMs }
        val overdueStage2 = stage2Deliveries.count { d ->
            val ts = parseIsoMillis(d.updatedAt)
            ts != null && ts < twoHoursAgoMs
        }

        IntakeStagesCounts(
            totalToday = totalToday,
            unloadingActive = unloading,
            overdueUnloading = overdueDrafts + overdueStage2,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeStagesCounts(),
    )

    private companion object {
        /** Совпадает со Stage2ListViewModel.STAGE2_STATUSES. */
        val STAGE2_STATUSES = listOf("filled")

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
