package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MainStatusUiState(
    val pendingOperations: Int = 0,
    val pendingSessions: Int = 0,
    val pendingAttachments: Int = 0,
    val pendingMutations: Int = 0,
    val pendingPhotos: Int = 0,
    val expectedToday: Int = 0,
    val expectedFuture: Int = 0,
) {
    // Кол-во ожидающих отправки сущностей: legacy (operations/sessions/attachments)
    // + новые offline-first мутации (приёмки/отгрузки) + фото со статусами
    // PENDING_UPLOAD / UPLOAD_ERROR. Используется индикатором синхронизации в
    // шапке main-экрана: > 0 → видна chip с числом, == 0 → скрыта.
    val totalPending: Int
        get() = pendingOperations + pendingSessions + pendingAttachments +
            pendingMutations + pendingPhotos
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
        combine(
            container.database.remoteSourceDocumentDao().observeAll(),
            container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
            container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
            container.stage1DraftRepository.observeAll(),
            container.database.remoteDeliveryDao().observeByStatus("filled"),
            ::PlanSources,
        ),
        dayTicker,
    ) { src, today ->
        val (docs, deliveryAttachedJsons, shipmentAttachedJsons, drafts, filledDeliveries) = src

        // Фильтр привязок — тот же, что в IntakeUpdSelectViewModel: иначе
        // устаревший локальный кэш привязанных УПД задвоит счётчик «Будущие».
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val updWithDraft: Set<String> = drafts.mapNotNull { it.updId }.toSet()

        // Today — только `expectedDate == today`. Прочерк/null/любая другая
        // дата — Future. УПД-drafts принудительно Today (с ними уже работают).
        var todayUnattachedCount = 0
        var futureUnattachedCount = 0
        for (d in docs) {
            if (d.id in attachedIds) continue
            if (d.id in updWithDraft) {
                todayUnattachedCount++
                continue
            }
            if (d.expectedDate == today) todayUnattachedCount++ else futureUnattachedCount++
        }

        // Empty-drafts (без УПД) — ручные приёмки, всегда Сегодня.
        val emptyDraftsCount = drafts.count { it.updId == null }

        // filled-deliveries с arrivedAt сегодня: «несут» УПД между 1 и 2 Этапом
        // (после Завершить 1 Этап УПД пропадает из snapshot инспектора).
        val filledTodayCount = filledDeliveries.count { isArrivedToday(it.arrivedAt, today) }

        val todayCount = todayUnattachedCount + emptyDraftsCount + filledTodayCount
        todayCount to futureUnattachedCount
    }

    private fun isArrivedToday(arrivedAt: String?, todayLocal: String): Boolean {
        if (arrivedAt.isNullOrBlank()) return false
        return runCatching {
            Instant.parse(arrivedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }.getOrNull() == todayLocal
    }

    /** Holder для агрегата 5 источников, чтобы рядом подмесить [dayTicker]. */
    private data class PlanSources(
        val docs: List<RemoteSourceDocumentEntity>,
        val deliveryAttachedJsons: List<String>,
        val shipmentAttachedJsons: List<String>,
        val drafts: List<Stage1DraftEntity>,
        val filledDeliveries: List<RemoteDeliveryEntity>,
    )

    // Новая offline-first очередь: мутации приёмок/отгрузок (mutations таблица,
    // conflictPending=0) + фото в статусах PENDING_UPLOAD/UPLOAD_ERROR на обоих
    // entity. Группируем в один Pair, чтобы combine выше не уперся в ограничение
    // 5 аргументов.
    private val newQueueCounts = combine(
        container.database.mutationDao().observePendingCount(),
        container.database.remoteDeliveryDao()
            .observePhotoCountByStatus(PHOTO_PENDING_STATUSES),
        container.database.remoteShipmentDao()
            .observePhotoCountByStatus(PHOTO_PENDING_STATUSES),
    ) { mutations, deliveryPhotos, shipmentPhotos ->
        mutations to (deliveryPhotos + shipmentPhotos)
    }

    val status: StateFlow<MainStatusUiState> = combine(
        container.operationRepository.observeUnsyncedCount(),
        container.database.receiptSessionDao()
            .observeCountBySyncStatuses(OperationRepository.UNSYNCED),
        container.attachmentRepository.observePendingCount(),
        expectedCounts,
        newQueueCounts,
    ) { ops, sessions, atts, expected, queue ->
        val (mutations, photos) = queue
        MainStatusUiState(
            pendingOperations = ops,
            pendingSessions = sessions,
            pendingAttachments = atts,
            pendingMutations = mutations,
            pendingPhotos = photos,
            expectedToday = expected.first,
            expectedFuture = expected.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainStatusUiState(),
    )

    companion object {
        /**
         * Тикер локальной даты раз в минуту: гарантирует, что после полуночи
         * счётчик «Сегодня/Будущие» пересчитается без действий пользователя.
         */
        private val dayTicker = flow {
            while (true) {
                emit(LocalDate.now().toString())
                delay(60_000L)
            }
        }.distinctUntilChanged()

        // Фото, ожидающие отправки на сервер: PENDING_UPLOAD — только что снято,
        // UPLOAD_ERROR — упало при попытке (retry queue). UPLOADING — уже в
        // процессе, считать его «висящим» бессмысленно. UPLOADED — отправлено.
        private val PHOTO_PENDING_STATUSES = listOf("PENDING_UPLOAD", "UPLOAD_ERROR")
    }
}
