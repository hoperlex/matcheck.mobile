package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.sourceDocTitlePrefix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Зеркало [Stage2DeliveryRow] для отгрузки. */
data class Stage2ShipmentRow(
    val shipment: RemoteShipmentEntity,
    val titleText: String,
    val subtitleText: String,
    val hasDraft: Boolean = false,
)

data class Stage2ShipmentGroup(
    val key: String,
    val displayName: String,
    val rows: List<Stage2ShipmentRow>,
)

/**
 * Зеркало [Stage2ListViewModel] для отгрузки. Статус `shipped` — отгружено,
 * ждёт подтверждения МОЛ; `confirmed_mol` — финализировано (уходит из списка).
 */
class DispatchStage2ListViewModel(container: AppContainer) : ViewModel() {

    /**
     * Жест «потянуть для обновления». Механика общая со всеми списками —
     * см. [SyncRefreshDelegate]: индикатор гаснет по факту синхронизации, а не
     * по факту постановки задачи, иначе при мёртвой сети он мигнёт и оставит
     * инспектора с прежним списком.
     */
    private val refreshDelegate = SyncRefreshDelegate(container.appContext, viewModelScope)

    val refreshState: StateFlow<SyncRefreshState> = refreshDelegate.state

    fun refresh() = refreshDelegate.refresh()

    fun consumeRefreshError() = refreshDelegate.consumeError()


    init {
        // См. Stage2ListViewModel.init: backfill только «своих» отгрузок,
        // чтобы не пингать сервер УПД из чужого объекта.
        viewModelScope.launch {
            combine(
                container.shipmentRepository.observeByStatuses(STAGE2_STATUSES),
                container.tokenStorage.state,
            ) { list, tokenSnapshot ->
                val currentSiteId = tokenSnapshot.effectiveSiteId
                if (currentSiteId.isNullOrBlank()) emptySet()
                else list
                    .filter { it.siteId == currentSiteId }
                    .flatMap { RemoteMappers.decodeIdList(it.sourceDocumentIdsJson) }
                    .toSet()
            }
                .distinctUntilChanged()
                .onEach { ids -> container.sourceDocumentBackfillService.ensureCached(ids) }
                .collect { }
        }
    }

    // Справочник контрагентов среди источников больше не нужен: имя поставщика
    // приходит денормализованным в самой УПД (`supplierName`), а получателя
    // этот экран не показывает.
    val groups: StateFlow<List<Stage2ShipmentGroup>> = combine(
        container.shipmentRepository.observeByStatuses(STAGE2_STATUSES),
        container.database.remoteSourceDocumentDao().observeAll(),
        container.shipmentStage2DraftRepository.observeIds(),
        container.tokenStorage.state,
    ) { shipments, sourceDocs, draftIds, tokenSnapshot ->
        // Defense-in-depth siteId-фильтр. См. Stage2ListViewModel.
        val currentSiteId = tokenSnapshot.effectiveSiteId
        if (currentSiteId.isNullOrBlank()) return@combine emptyList()
        val ownShipments = shipments.filter { it.siteId == currentSiteId }

        val docById = sourceDocs.associateBy { it.id }
        val draftIdSet = draftIds.toSet()

        val pairs = ownShipments.map { s ->
            val attachedIds = RemoteMappers.decodeIdList(s.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }

            val updNumbers = attachedDocs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
            val updNumberText = when {
                updNumbers.isNotEmpty() -> buildUpdSummary(updNumbers)
                else -> extractManualUpd(s.comment)?.takeIf { it.isNotBlank() } ?: "—"
            }
            // Префикс по kind — для отгрузки чаще «Накладная» (ТН-2116/ОС-2),
            // но для ручных без привязки оставляем «УПД» (см. Stage2ListViewModel).
            val prefix = attachedDocs.firstOrNull()?.kind
                ?.let(::sourceDocTitlePrefix) ?: "УПД"
            val titleText = "$prefix $updNumberText"

            // Подзаголовок — госномер авто, введённый инспектором на 1 Этапе.
            // На этом экране он точно известен (без госномера 1 Этап не
            // завершается). Без префикса «Госномер:» — короче и нагляднее.
            val subtitleText = s.vehiclePlate?.takeIf { it.isNotBlank() } ?: "—"

            // У RemoteShipmentEntity нет своего supplierId (на сервере
            // shipments.supplier_id есть, в Room-сущность не смаплен), поэтому
            // имя поставщика берём единственным путём — из привязанной УПД.
            // Для отгрузки «поставщик» — это мы сами, так что группа обычно
            // одна; группируем как приёмку ради единообразия экранов.
            val supplierName = attachedDocs.firstNotNullOfOrNull {
                it.supplierName?.takeIf(String::isNotBlank)
            }

            val row = Stage2ShipmentRow(
                shipment = s,
                titleText = titleText,
                subtitleText = subtitleText,
                hasDraft = s.id in draftIdSet,
            )
            row to supplierName
        }

        groupByParty(pairs) { it.second }
            .map { group ->
                Stage2ShipmentGroup(group.key, group.displayName, group.rows.map { it.first })
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        const val UPD_SUMMARY_MAX_INLINE = 2
        val STAGE2_STATUSES = listOf("shipped")
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")

        fun buildUpdSummary(numbers: List<String>): String {
            if (numbers.size <= UPD_SUMMARY_MAX_INLINE) return numbers.joinToString(", ")
            val head = numbers.take(UPD_SUMMARY_MAX_INLINE).joinToString(", ")
            return "$head +${numbers.size - UPD_SUMMARY_MAX_INLINE}"
        }

        fun extractManualUpd(comment: String?): String? {
            if (comment.isNullOrBlank()) return null
            return MANUAL_UPD_REGEX.find(comment)?.groupValues?.getOrNull(1)?.trim()
        }
    }
}
