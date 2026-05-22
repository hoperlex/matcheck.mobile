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
 * План «Сегодня/Будущие» считаем по тем же ожидаемым УПД, что показываются
 * на экране выбора УПД (1 Этап) — серверная таблица `remote_source_documents`
 * минус локально известные привязки к Delivery/Shipment.
 *
 * Сегодня: `expectedDate == LocalDate.now()` (формат сервера — 'YYYY-MM-DD',
 * без TZ). Всё остальное — пустое `expectedDate` или любая другая дата —
 * считаем «Будущим».
 */
class MainStatusViewModel(container: AppContainer) : ViewModel() {

    private val expectedCounts = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
    ) { docs, deliveryAttachedJsons, shipmentAttachedJsons ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val today = LocalDate.now().toString()
        var todayCount = 0
        var futureCount = 0
        for (d in docs) {
            if (d.id in attachedIds) continue
            if (d.expectedDate == today) todayCount++ else futureCount++
        }
        todayCount to futureCount
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
