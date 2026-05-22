package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class MainStatusUiState(
    val pendingOperations: Int = 0,
    val pendingSessions: Int = 0,
    val pendingAttachments: Int = 0,
    val expectedToday: Int = 0,
    val expectedFuture: Int = 0,
) {
    val totalPending: Int get() = pendingOperations + pendingSessions + pendingAttachments
}

/**
 * План «Сегодня/Будущие» — счётчик активной работы инспектора.
 *
 * Сегодня = (1) empty-drafts из stage1_drafts (ручные приёмки без УПД),
 * (2) drafts с привязкой к УПД, (3) неприкреплённые УПД с `expectedDate ==
 * сегодня`, у которых ещё нет draft, (4) delivery в статусе `filled` —
 * 1 Этап завершён, 2 Этап не пройден.
 *
 * Будущие = неприкреплённые УПД без draft с другой/пустой `expectedDate`.
 *
 * Когда пользователь жмёт «Завершить 2 Этап», delivery переходит в
 * `confirmed_mol` → выпадает из (4), счётчик «Сегодня» уменьшается.
 * Когда создаётся ручная приёмка — она попадает в (1), счётчик растёт.
 */
class MainStatusViewModel(container: AppContainer) : ViewModel() {

    private val expectedCounts = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
        container.stage1DraftRepository.observeAll(),
        container.database.remoteDeliveryDao().observeByStatus("filled"),
    ) { docs, deliveryAttachedJsons, shipmentAttachedJsons, drafts, filledDeliveries ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val today = LocalDate.now().toString()
        val updWithDraft: Set<String> = drafts.mapNotNull { it.updId }.toSet()

        // (1) + (2): все drafts. Empty + по УПД.
        val draftsCount = drafts.size

        // (3): неприкреплённые УПД на сегодня без существующего draft (чтобы
        // не задвоить с пунктом (2)).
        var unattachedTodayCount = 0
        var unattachedFutureCount = 0
        for (d in docs) {
            if (d.id in attachedIds) continue
            if (d.id in updWithDraft) continue
            if (d.expectedDate == today) unattachedTodayCount++ else unattachedFutureCount++
        }

        // (4): delivery со статусом filled — 1 этап завершён, 2 ещё не пройден.
        val activeFilledCount = filledDeliveries.size

        val todayCount = draftsCount + unattachedTodayCount + activeFilledCount
        todayCount to unattachedFutureCount
    }

    val status: StateFlow<MainStatusUiState> = combine(
        container.operationRepository.observeUnsyncedCount(),
        container.database.receiptSessionDao()
            .observeCountBySyncStatuses(OperationRepository.UNSYNCED),
        container.attachmentRepository.observePendingCount(),
        expectedCounts,
    ) { ops, sessions, atts, expected ->
        MainStatusUiState(
            pendingOperations = ops,
            pendingSessions = sessions,
            pendingAttachments = atts,
            expectedToday = expected.first,
            expectedFuture = expected.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainStatusUiState(),
    )
}
