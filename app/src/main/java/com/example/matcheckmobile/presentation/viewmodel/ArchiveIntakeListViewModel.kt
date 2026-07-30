package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.BusinessTime
import com.example.matcheckmobile.domain.model.sourceDocTitlePrefix
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Архив завершённых приёмок за последние 7 дней — локальный (Room), без сети.
 *
 * Завершёнными считаем приёмки в статусе `confirmed_mol` («Подтверждено МОЛ»
 * на веб-портале): сюда попадают как обычные 1+2 этапа, так и «Ручной внос»,
 * который сразу пишется как confirmed_mol.
 *
 * Группировка по дате — локальная зона устройства. Точка отсчёта (что считать
 * «датой приёмки») — лучшее из доступного: arrivedAt → confirmedByMolAt →
 * updatedAt. arrivedAt — это момент «Завершить 1 Этап» (Stage1FormViewModel),
 * визуально совпадает с «датой заезда» в карточке.
 */
data class ArchiveIntakeRow(
    val delivery: RemoteDeliveryEntity,
    val updNumber: String,
    val vehiclePlate: String,
    val arrivedAtMs: Long?,
    val confirmedAtMs: Long?,
)

/** Состояние pull-to-refresh на экранах архива. */
data class ArchiveRefreshState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class ArchiveIntakeDayGroup(
    /** Метка отображения, например «14.06.26». */
    val dateLabel: String,
    /** Сортировочный ключ — миллисекунды начала локального дня. */
    val dayStartMs: Long,
    val rows: List<ArchiveIntakeRow>,
)

class ArchiveIntakeListViewModel(private val container: AppContainer) : ViewModel() {

    private val _refreshState = MutableStateFlow(ArchiveRefreshState())

    /** Состояние жеста «потянуть для обновления»: индикатор + текст ошибки. */
    val refreshState: StateFlow<ArchiveRefreshState> = _refreshState.asStateFlow()

    /**
     * Синхронизация по жесту. Ждём фактического завершения sync-задачи
     * (не просто постановки в очередь) и сообщаем об ошибке — иначе инспектор
     * видел бы «обновилось», хотя обмен не состоялся и архив по-прежнему
     * отличается от другого планшета.
     */
    fun refresh() {
        if (_refreshState.value.isRefreshing) return
        _refreshState.value = ArchiveRefreshState(isRefreshing = true)
        viewModelScope.launch {
            val state = runCatching {
                MatcheckSyncScheduler.requestImmediateSyncAndAwait(container.appContext)
            }.getOrNull()
            _refreshState.value = ArchiveRefreshState(
                isRefreshing = false,
                error = if (state == WorkInfo.State.SUCCEEDED) null else SYNC_FAILED_MESSAGE,
            )
        }
    }

    fun consumeRefreshError() {
        _refreshState.update { it.copy(error = null) }
    }

    init {
        // Архив тянет docNumber через тот же путь, что и Stage2List, поэтому
        // также прогреваем backfill — иначе у не-привязанных УПД заголовок
        // будет «—» до следующего sync. Backfill безопасно вызывать и для
        // чужих siteId (внутри идёт find-or-fetch по id), но всё равно фильтр
        // ниже не пускает чужие записи в UI.
        viewModelScope.launch {
            container.deliveryRepository.observeByStatuses(ARCHIVE_STATUSES)
                .map { list ->
                    list.flatMap { RemoteMappers.decodeIdList(it.sourceDocumentIdsJson) }.toSet()
                }
                .distinctUntilChanged()
                .onEach { ids -> container.sourceDocumentBackfillService.ensureCached(ids) }
                .collect { }
        }
    }

    val groups: StateFlow<List<ArchiveIntakeDayGroup>> = combine(
        container.deliveryRepository.observeByStatuses(ARCHIVE_STATUSES),
        container.database.remoteSourceDocumentDao().observeAll(),
        container.tokenStorage.state,
    ) { deliveries, sourceDocs, tokenSnapshot ->
        // Defense-in-depth: даже если /sync уже фильтрует записи по siteId
        // инспектора, явно отсекаем чужие siteId на уровне UI. При
        // currentSiteId == null (объект не привязан / только что отвязан /
        // токен сброшен) показываем пустой архив, а не «все, что лежит в БД».
        val currentSiteId = tokenSnapshot.effectiveSiteId
        if (currentSiteId.isNullOrBlank()) return@combine emptyList()

        val ownDeliveries = deliveries.filter { it.siteId == currentSiteId }
        val docById = sourceDocs.associateBy { it.id }
        // Зона фиксирована (см. BusinessTime): при ZoneId.systemDefault()
        // два планшета с разными настройками времени разносили одну запись по
        // разным дням, и архив «не совпадал». Окно — семь КАЛЕНДАРНЫХ дат
        // (сегодня и шесть предыдущих), а не 168 часов.
        val zone = BusinessTime.ZONE
        val windowStartMs = BusinessTime.windowStartMs(ARCHIVE_WINDOW_DAYS)

        val rows = ownDeliveries.mapNotNull { d ->
            val arrivedAtMs = parseMs(d.arrivedAt)
            val confirmedAtMs = parseMs(d.confirmedByMolAt)
            val updatedAtMs = parseMs(d.updatedAt)
            // «Дата приёмки» для окна архива — arrived → confirmed → updated.
            // arrived обычно есть всегда (Stage1 проставляет), для ручного
            // вноса его нет — там опираемся на confirmed/updated.
            val anchorMs = arrivedAtMs ?: confirmedAtMs ?: updatedAtMs ?: return@mapNotNull null
            if (anchorMs < windowStartMs) return@mapNotNull null

            val attachedIds = RemoteMappers.decodeIdList(d.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }
            val updNumbers = attachedDocs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
            val updFromComment = extractManualUpd(d.comment)?.takeIf { it.isNotBlank() }
            val updText = when {
                updNumbers.isNotEmpty() -> buildUpdSummary(updNumbers)
                updFromComment != null -> updFromComment
                else -> "—"
            }
            val prefix = attachedDocs.firstOrNull()?.kind?.let(::sourceDocTitlePrefix) ?: "УПД"
            val title = "$prefix $updText"

            val plate = d.vehiclePlate?.takeIf { it.isNotBlank() } ?: "—"

            ArchiveIntakeRow(
                delivery = d,
                updNumber = title,
                vehiclePlate = plate,
                arrivedAtMs = arrivedAtMs,
                confirmedAtMs = confirmedAtMs,
            ) to anchorMs
        }

        rows
            .groupBy { (_, anchor) ->
                Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
            }
            .map { (date, list) ->
                // Вторичный ключ — id: при одинаковом timestamp порядок иначе
                // не детерминирован и на двух планшетах карточки шли по-разному.
                val sorted = list
                    .sortedWith(
                        compareByDescending<Pair<ArchiveIntakeRow, Long>> { it.second }
                            .thenBy { it.first.delivery.id },
                    )
                    .map { it.first }
                ArchiveIntakeDayGroup(
                    dateLabel = date.format(DATE_LABEL_FMT),
                    dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    rows = sorted,
                )
            }
            .sortedByDescending { it.dayStartMs }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        const val SYNC_FAILED_MESSAGE = "Не удалось обновить — проверьте связь"
        const val UPD_SUMMARY_MAX_INLINE = 2
        val ARCHIVE_STATUSES = listOf("confirmed_mol")
        /** Семь календарных дат: сегодня и шесть предыдущих. Зона — [BusinessTime.ZONE]. */
        const val ARCHIVE_WINDOW_DAYS = 7L
        // Формат как в задаче: «14.06.26» (двузначный год). Чисто визуальная
        // метка группы — sort идёт по dayStartMs, а не по строке.
        val DATE_LABEL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")

        fun parseMs(iso: String?): Long? =
            iso?.takeIf { it.isNotBlank() }?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }

        fun extractManualUpd(comment: String?): String? {
            if (comment.isNullOrBlank()) return null
            return MANUAL_UPD_REGEX.find(comment)?.groupValues?.getOrNull(1)?.trim()
        }

        fun buildUpdSummary(numbers: List<String>): String {
            if (numbers.size <= UPD_SUMMARY_MAX_INLINE) return numbers.joinToString(", ")
            val head = numbers.take(UPD_SUMMARY_MAX_INLINE).joinToString(", ")
            return "$head +${numbers.size - UPD_SUMMARY_MAX_INLINE}"
        }
    }
}

