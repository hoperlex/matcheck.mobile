package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
 * План «Сегодня/Будущие» — счётчик ожидаемых УПД на сегодня.
 *
 * Сегодня =
 *   (a) УПД из server-snapshot с `expectedDate == сегодня` (всё, что
 *       автоматически загружено с веб-портала на сегодня),
 * + (b) ручные приёмки (empty-drafts без updId),
 * + (c) delivery со статусом `filled` (1 этап завершён, 2 не пройден) у
 *       которой `arrivedAt` приходится на сегодняшнюю дату — это та же УПД
 *       из (a), просто после «Завершить 1 Этап» она пропадает из server-
 *       snapshot для inspector_kpp; сюда её подхватываем, чтобы счётчик
 *       не «прыгал».
 *
 * Будущие = УПД из server-snapshot с другой/пустой `expectedDate`.
 *
 * Поведенческие требования:
 * - Создал ручную приёмку → +1 в Сегодня (через (b)).
 * - Завершил 1 Этап по УПД → УПД ушла из docs, но delivery с arrivedAt=
 *   сегодня попала в (c) → счётчик не меняется.
 * - Завершил 2 Этап → delivery `filled` → `confirmed_mol`, выпадает из
 *   `observeByStatus("filled")` → −1 в Сегодня.
 */
class MainStatusViewModel(container: AppContainer) : ViewModel() {

    private val expectedCounts = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.stage1DraftRepository.observeAll(),
        container.database.remoteDeliveryDao().observeByStatus("filled"),
    ) { docs, drafts, filledDeliveries ->
        val today = LocalDate.now().toString()

        // (a): неприкреплённые УПД из snapshot, разводим по expectedDate.
        var todayUnattachedCount = 0
        var futureUnattachedCount = 0
        for (d in docs) {
            if (d.expectedDate == today) todayUnattachedCount++ else futureUnattachedCount++
        }

        // (b): только empty-drafts. УПД-drafts (с updId) уже учтены в (a),
        // потому что у них пока нет delivery → УПД остаётся в snapshot.
        val emptyDraftsCount = drafts.count { it.updId == null }

        // (c): filled-deliveries с arrivedAt сегодня. Между этапами счётчик
        // должен «нести» УПД, пропавшую из snapshot после Завершить 1 Этап.
        val filledTodayCount = filledDeliveries.count { isToday(it.arrivedAt, today) }

        val todayCount = todayUnattachedCount + emptyDraftsCount + filledTodayCount
        todayCount to futureUnattachedCount
    }

    private fun isToday(arrivedAt: String?, todayLocal: String): Boolean {
        if (arrivedAt.isNullOrBlank()) return false
        return runCatching {
            Instant.parse(arrivedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }.getOrNull() == todayLocal
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
