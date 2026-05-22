package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * Inbox-список ожидаемых УПД. Источник — серверная таблица
 * `remote_source_documents`, заполняемая через [SyncRepository.pullDelta].
 *
 * Сервер отдаёт inspector_kpp только не привязанные к приёмке/отгрузке УПД,
 * но дельта-sync не удаляет локально те, что стали привязанными после
 * первичной загрузки (их нет ни в response, ни в `deletedIds`). Чтобы UI
 * не показывал «зомби» из устаревшего кэша, фильтруем по локально известным
 * привязкам Delivery + Shipment.
 *
 * Делим на «Сегодня» / «Будущие» по `expectedDate` (формат сервера
 * 'YYYY-MM-DD'). Сегодня — `expectedDate == LocalDate.now()`,
 * иначе (другая дата или null) — Будущие.
 */
/**
 * Строка списка УПД для выбора. Бывает двух «видов»:
 * - реальная УПД из server-snapshot (`document != null`): тап → форма по `updId`;
 *   `draftId != null` означает, что у УПД уже есть начатый локальный draft —
 *   рисуем бейдж «Начато»;
 * - empty-draft без УПД (`document == null`, `draftId != null`): создан кнопкой
 *   «Создать приёмку», после первого фото и возврата назад попадает в список.
 */
data class IntakeUpdRow(
    val document: RemoteSourceDocumentEntity?,
    val supplierName: String?,
    val contractorName: String?,
    val draftId: String? = null,
)

/** Группа УПД по подрядчику. Используется для секций в [IntakeUpdSelectScreen]. */
data class IntakeUpdGroup(
    val contractorName: String,
    val rows: List<IntakeUpdRow>,
)

data class IntakeUpdGroupsState(
    val today: List<IntakeUpdGroup> = emptyList(),
    val future: List<IntakeUpdGroup> = emptyList(),
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"
private const val MANUAL_GROUP_LABEL = "Созданы вручную"

/**
 * УПД считается «Будущим» если `expectedDate` — корректная дата строго
 * больше сегодняшней (по локальной таймзоне). Пустое/прошлое/невалидное —
 * Today (надо разобрать сейчас).
 */
internal fun isStrictlyFuture(expectedDate: String?, today: LocalDate): Boolean {
    val raw = expectedDate?.takeIf { it.isNotBlank() } ?: return false
    val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return false
    return parsed.isAfter(today)
}

class IntakeUpdSelectViewModel(container: AppContainer) : ViewModel() {

    val state: StateFlow<IntakeUpdGroupsState> = combine(
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteCounterpartyDao().observeAll(),
        container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
        container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
        container.stage1DraftRepository.observeAll(),
    ) { docs, cps, deliveryAttachedJsons, shipmentAttachedJsons, drafts ->
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val byCounterpartyId = cps.associateBy { it.id }
        val today = LocalDate.now()
        // updId → draftId. По uniqueness индекса значение единственное.
        val draftsByUpdId: Map<String, String> = drafts
            .mapNotNull { d -> d.updId?.let { it to d.localDraftId } }
            .toMap()
        val emptyDrafts: List<Stage1DraftEntity> = drafts.filter { it.updId == null }

        val todayRows = mutableListOf<IntakeUpdRow>()
        val futureRows = mutableListOf<IntakeUpdRow>()
        for (d in docs) {
            if (d.id in attachedIds) continue
            val draftId = draftsByUpdId[d.id]
            val row = IntakeUpdRow(
                document = d,
                supplierName = d.supplierName
                    ?: d.supplierId?.let { byCounterpartyId[it]?.name },
                contractorName = d.contractorName
                    ?: d.contractorId?.let { byCounterpartyId[it]?.name },
                draftId = draftId,
            )
            // Future — только expectedDate > сегодня. Всё прочее (сегодня,
            // прошлое, null, есть draft) идёт в Today: пользователь работает
            // с ним сейчас.
            val bucket = if (draftId == null && isStrictlyFuture(d.expectedDate, today))
                futureRows else todayRows
            bucket.add(row)
        }
        // Empty drafts (без УПД) показываем только в «Сегодня» — это активные
        // приёмки текущего инспектора.
        for (draft in emptyDrafts) {
            todayRows.add(
                IntakeUpdRow(
                    document = null,
                    supplierName = null,
                    contractorName = null,
                    draftId = draft.localDraftId,
                ),
            )
        }
        IntakeUpdGroupsState(
            today = groupByContractor(todayRows),
            future = groupByContractor(futureRows),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeUpdGroupsState(),
    )

    /**
     * Группировка: empty-draft строки (`document == null`) идут в отдельный
     * блок «Созданы вручную» в самом конце списка. Реальные УПД из веб-портала
     * группируются по подрядчику; те, у которых contractorName пуст —
     * в «Подрядчик не указан» прямо перед «Созданы вручную».
     */
    private fun groupByContractor(rows: List<IntakeUpdRow>): List<IntakeUpdGroup> {
        val (manualRows, realRows) = rows.partition { it.document == null }
        val realGroups = realRows
            .groupBy { it.contractorName?.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL }
            .toList()
            .sortedWith(
                compareBy(
                    { it.first == UNKNOWN_CONTRACTOR_LABEL },
                    { it.first.lowercase() },
                ),
            )
            .map { (contractor, items) -> IntakeUpdGroup(contractor, items) }
        return if (manualRows.isEmpty()) realGroups
            else realGroups + IntakeUpdGroup(MANUAL_GROUP_LABEL, manualRows)
    }
}
