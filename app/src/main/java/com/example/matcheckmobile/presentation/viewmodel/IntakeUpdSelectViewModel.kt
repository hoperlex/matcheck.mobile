package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.BusinessTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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

/**
 * Группа УПД по поставщику. Используется для секций в [IntakeUpdSelectScreen].
 *
 * [key] — стабильный ключ для Compose, [displayName] — заголовок на экране;
 * почему они разные, см. [PartyGroup].
 */
data class IntakeUpdGroup(
    val key: String,
    val displayName: String,
    val rows: List<IntakeUpdRow>,
)

data class IntakeUpdGroupsState(
    val today: List<IntakeUpdGroup> = emptyList(),
    val future: List<IntakeUpdGroup> = emptyList(),
)

private const val MANUAL_GROUP_LABEL = "Созданы вручную"

class IntakeUpdSelectViewModel(container: AppContainer) : ViewModel() {

    val state: StateFlow<IntakeUpdGroupsState> = combine(
        combine(
            container.database.remoteSourceDocumentDao().observeAll(),
            container.database.remoteCounterpartyDao().observeAll(),
            container.database.remoteDeliveryDao().observeAttachedSourceDocumentIdsJson(),
            container.database.remoteShipmentDao().observeAttachedSourceDocumentIdsJson(),
            container.stage1DraftRepository.observeAll(),
            ::IntakeUpdSources,
        ),
        dayTicker,
        container.tokenStorage.state,
    ) { src, today, tokenSnapshot ->
        // Defense-in-depth: даже если /sync фильтрует УПД по siteId инспектора
        // на сервере, stale-запись может остаться в локальной Room после
        // смены аккаунта (см. UpdSelectFilter). Fail-closed: при пустом
        // siteId возвращаем пустой список — лучше пусто, чем чужое.
        val currentSiteId = tokenSnapshot.siteId
        if (currentSiteId.isNullOrBlank()) return@combine IntakeUpdGroupsState()

        val (docs, cps, deliveryAttachedJsons, shipmentAttachedJsons, drafts) = src
        val attachedIds: Set<String> = buildSet {
            (deliveryAttachedJsons + shipmentAttachedJsons).forEach { json ->
                addAll(RemoteMappers.decodeIdList(json))
            }
        }
        val byCounterpartyId = cps.associateBy { it.id }
        // updId → draftId. По uniqueness индекса значение единственное.
        val draftsByUpdId: Map<String, String> = drafts
            .mapNotNull { d -> d.updId?.let { it to d.localDraftId } }
            .toMap()
        val emptyDrafts: List<Stage1DraftEntity> = drafts.filter { it.updId == null }

        // Фильтр: direction + not-attached + siteId. Смотри UpdSelectFilter.kt
        // и UpdSelectFilterTest — единый источник истины для этого правила.
        val ownDocs = filterUpdDocsForSite(
            docs = docs,
            currentSiteId = currentSiteId,
            direction = "inbound",
            attachedIds = attachedIds,
        )

        val todayRows = mutableListOf<IntakeUpdRow>()
        val futureRows = mutableListOf<IntakeUpdRow>()
        for (d in ownDocs) {
            val draftId = draftsByUpdId[d.id]
            val row = IntakeUpdRow(
                document = d,
                // takeIf(isNotBlank): пустая, но не-null строка не должна
                // блокировать fallback на справочник контрагентов.
                supplierName = d.supplierName?.takeIf(String::isNotBlank)
                    ?: d.supplierId?.let { byCounterpartyId[it]?.name },
                contractorName = d.contractorName?.takeIf(String::isNotBlank)
                    ?: d.contractorId?.let { byCounterpartyId[it]?.name },
                draftId = draftId,
            )
            // Today — только expectedDate == today. Прочерк/null/любая другая
            // дата (вкл. прошлое и будущее) → Future. Если есть draft —
            // принудительно Today: пользователь уже работает с этой УПД.
            val bucket = when {
                draftId != null -> todayRows
                d.expectedDate == today -> todayRows
                else -> futureRows
            }
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
            today = groupBySupplier(todayRows),
            future = groupBySupplier(futureRows),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntakeUpdGroupsState(),
    )

    /**
     * Группировка: empty-draft строки (`document == null`) идут в отдельный
     * блок «Созданы вручную» в самом конце списка. Реальные УПД из веб-портала
     * группируются по поставщику; те, у которых supplierName пуст —
     * в «Поставщик не указан» прямо перед «Созданы вручную».
     *
     * Поставщик, а не подрядчик: подрядчик на объекте один на все документы,
     * то есть группировка по нему вырождается в единственную группу, а
     * к воротам приезжает конкретный поставщик — по нему инспектор и ищет
     * свою УПД. Склейку разных написаний одного имени берёт на себя
     * [groupByParty].
     */
    private fun groupBySupplier(rows: List<IntakeUpdRow>): List<IntakeUpdGroup> {
        val (manualRows, realRows) = rows.partition { it.document == null }
        val realGroups = groupByParty(realRows) { it.supplierName }
            .map { IntakeUpdGroup(it.key, it.displayName, it.rows) }
        return if (manualRows.isEmpty()) realGroups
            else realGroups + IntakeUpdGroup(MANUAL_PARTY_KEY, MANUAL_GROUP_LABEL, manualRows)
    }

    /**
     * Внутренний holder, чтобы прокинуть 5 источников через `combine` и
     * рядом подмешать тикер дня. Иначе пришлось бы использовать combine с
     * vararg, который требует одинакового типа для всех источников.
     */
    private data class IntakeUpdSources(
        val docs: List<RemoteSourceDocumentEntity>,
        val counterparties: List<com.example.matcheckmobile.data.local.entity.RemoteCounterpartyEntity>,
        val deliveryAttachedJsons: List<String>,
        val shipmentAttachedJsons: List<String>,
        val drafts: List<Stage1DraftEntity>,
    )

    companion object {
        /**
         * Тикер, эмитящий текущую локальную дату каждые 60 сек. Нужен чтобы
         * УПД c `expectedDate=завтра` сама переехала из «Будущие» в «Сегодня»
         * сразу после полуночи без действий пользователя. Без него `today`
         * пересчитывается только когда переэмитит один из data-источников.
         */
        private val dayTicker = flow {
            while (true) {
                emit(BusinessTime.todayIso())
                delay(60_000L)
            }
        }.distinctUntilChanged()
    }
}
