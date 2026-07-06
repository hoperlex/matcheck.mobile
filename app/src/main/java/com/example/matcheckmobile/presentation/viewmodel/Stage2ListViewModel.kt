package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
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

/**
 * Одна строка в списке 2 Этапа — приёмка, ожидающая подтверждения МОЛ.
 *
 * [titleText] — что показать крупно в карточке: «УПД №…» если номер удалось
 * резолвить (по привязанной УПД либо по строке «УПД: …» в комментарии),
 * иначе «Госномер …», иначе короткий id приёмки.
 * [subtitleText] — мелкая строка под заголовком, обычно «Поставщик: …».
 */
data class Stage2DeliveryRow(
    val delivery: RemoteDeliveryEntity,
    val titleText: String,
    val subtitleText: String,
    /** true → есть локальный stage2_draft → бейдж «Начато» на карточке. */
    val hasDraft: Boolean = false,
)

/** Группа приёмок по подрядчику — структура зеркалит [IntakeUpdGroup] на 1 Этапе. */
data class Stage2DeliveryGroup(
    val contractorName: String,
    val rows: List<Stage2DeliveryRow>,
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"

/**
 * Список приёмок, ожидающих 2 Этап — статус `filled` («Оформлена» на
 * веб-портале). Подтверждение МОЛ переводит их в `confirmed_mol`, после
 * чего они уходят из этого списка. UI показывает группировку по подрядчику
 * с раскрытием — так же, как «Выбор УПД для приёмки» на 1 Этапе.
 *
 * Раньше сюда же входил статус `no_document` (приёмка без УПД), но веб
 * (commit fd0552c) упразднил этот код: теперь «без документа» — производный
 * признак `sourceDocumentIds.length == 0`, а статус таких приёмок —
 * `not_filled`. На 2 Этап их не пускаем: до МОЛ нужен оформленный `filled`.
 */
class Stage2ListViewModel(container: AppContainer) : ViewModel() {

    init {
        // /sync для inspector_kpp фильтрует УПД, привязанные к приёмке/отгрузке,
        // поэтому для filled-приёмок их docNumber приходится дотягивать
        // индивидуальными GET'ами. Подписка идёт пока ViewModel жив.
        // Дотягиваем только «свои» приёмки — сервер УПД чужого объекта всё
        // равно не отдаст, но так экономим сетевые запросы и не пингуем 404
        // за stale-записи чужого объекта в Room.
        viewModelScope.launch {
            combine(
                container.deliveryRepository.observeByStatuses(STAGE2_STATUSES),
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

    val groups: StateFlow<List<Stage2DeliveryGroup>> = combine(
        container.deliveryRepository.observeByStatuses(STAGE2_STATUSES),
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteCounterpartyDao().observeAll(),
        container.stage2DraftRepository.observeIds(),
        container.tokenStorage.state,
    ) { deliveries, sourceDocs, counterparties, draftIds, tokenSnapshot ->
        // Defense-in-depth: фильтруем stale-приёмки чужого объекта из Room.
        // Fail-closed при пустом siteId — пустой список.
        val currentSiteId = tokenSnapshot.effectiveSiteId
        if (currentSiteId.isNullOrBlank()) return@combine emptyList()
        val ownDeliveries = deliveries.filter { it.siteId == currentSiteId }

        val cpById = counterparties.associateBy { it.id }
        val docById = sourceDocs.associateBy { it.id }
        val draftIdSet = draftIds.toSet()

        val pairs = ownDeliveries.map { d ->
            val attachedIds = RemoteMappers.decodeIdList(d.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }

            val updNumbers = attachedDocs.mapNotNull { it.docNumber?.takeIf { n -> n.isNotBlank() } }
            // Fallback: УПД могла исчезнуть из remote_source_documents после
            // привязки (сервер фильтрует «непривязанные»). Тогда тянем номер
            // из комментария — Stage1FormViewModel пишет туда «УПД: …»
            // при ручном вводе или просто как маркер.
            val updNumberText = when {
                updNumbers.isNotEmpty() -> buildUpdSummary(updNumbers)
                else -> extractManualUpd(d.comment)?.takeIf { it.isNotBlank() } ?: "—"
            }
            // Префикс по kind первой привязанной — УПД vs Накладная. Если все
            // привязки одного типа, остальные подтянутся под тот же префикс;
            // mixed-bundle (УПД + ТН на одной приёмке) на практике не бывает.
            // Для ручных приёмок без привязки (только текст из комментария)
            // оставляем «УПД» по умолчанию — Stage1 пишет ручную метку как
            // «УПД: …», kind неизвестен, но семантически это УПД-реквизит.
            val prefix = attachedDocs.firstOrNull()?.kind
                ?.let(::sourceDocTitlePrefix) ?: "УПД"
            val titleText = "$prefix $updNumberText"

            // Подзаголовок — госномер авто, введённый инспектором на 1 Этапе.
            // На этом экране он уже точно известен (без госномера 1 Этап не
            // финализируется), поэтому показываем сразу номер, без префикса
            // «Госномер:» — экономит место и читается быстрее.
            val subtitleText = d.vehiclePlate?.takeIf { it.isNotBlank() } ?: "—"

            val contractorName = d.contractorId?.let { cpById[it]?.name }
                ?: attachedDocs.firstOrNull()?.contractorName

            val row = Stage2DeliveryRow(
                delivery = d,
                titleText = titleText,
                subtitleText = subtitleText,
                hasDraft = d.id in draftIdSet,
            )
            val groupKey = contractorName?.takeIf { it.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL
            row to groupKey
        }

        pairs.groupBy({ it.second }, { it.first })
            .toList()
            .sortedWith(
                compareBy(
                    { it.first == UNKNOWN_CONTRACTOR_LABEL },
                    { it.first.lowercase() },
                ),
            )
            .map { (contractor, items) -> Stage2DeliveryGroup(contractor, items) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        /** Лимит на «явные» номера в сводке, остальное прячем под «+N». */
        const val UPD_SUMMARY_MAX_INLINE = 2

        /** Статусы приёмки, попадающие в список 2-го Этапа. */
        val STAGE2_STATUSES = listOf("filled")

        /**
         * Строка «УПД: <number>» либо «Примечание: <number>» в комментарии приёмки.
         * Stage1FormViewModel пишет ручной УПД с префиксом «Примечание:» — без него
         * заголовок карточки приёмки без УПД был бы пустым («УПД —»).
         */
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
