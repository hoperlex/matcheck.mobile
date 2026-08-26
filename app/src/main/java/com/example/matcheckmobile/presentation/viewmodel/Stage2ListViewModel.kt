package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.attachedDocsGroupTitle
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
 * [subtitleText] — мелкая строка под заголовком: госномер авто с 1 Этапа.
 */
data class Stage2DeliveryRow(
    val delivery: RemoteDeliveryEntity,
    val titleText: String,
    val subtitleText: String,
    /** true → есть локальный stage2_draft → бейдж «Начато» на карточке. */
    val hasDraft: Boolean = false,
)

/** Группа приёмок по поставщику — структура зеркалит [IntakeUpdGroup] на 1 Этапе. */
data class Stage2DeliveryGroup(
    val key: String,
    val displayName: String,
    val rows: List<Stage2DeliveryRow>,
)

/**
 * Список приёмок, ожидающих 2 Этап — статус `filled` («Оформлена» на
 * веб-портале). Подтверждение МОЛ переводит их в `confirmed_mol`, после
 * чего они уходят из этого списка. UI показывает группировку по поставщику
 * с раскрытием — так же, как «Выбор УПД для приёмки» на 1 Этапе.
 *
 * Раньше сюда же входил статус `no_document` (приёмка без УПД), но веб
 * (commit fd0552c) упразднил этот код: теперь «без документа» — производный
 * признак `sourceDocumentIds.length == 0`, а статус таких приёмок —
 * `not_filled`. На 2 Этап их не пускаем: до МОЛ нужен оформленный `filled`.
 */
class Stage2ListViewModel(container: AppContainer) : ViewModel() {

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

            // Тот же заголовок, что инспектор видел на 1 Этапе: номера
            // сгруппированы по видам («УПД 1403, 1404 · Накладная 192»), ничего
            // не обрезано, документ без номера показан как «—».
            //
            // Прежний код брал ОДИН префикс по первому привязанному документу,
            // обосновывая это тем, что «mixed-bundle (УПД + ТН на одной приёмке)
            // на практике не бывает». На боевых данных такие приёмки есть, и
            // накладные в них подписывались как «УПД»; вдобавок префикс зависел
            // от порядка id в sourceDocumentIdsJson, а не от вида документа.
            // «Без УПД» здесь не бывает — приёмка со статусом filled всегда
            // оформлена, поэтому null отдаём как «УПД —».
            val titleText = attachedDocsGroupTitle(attachedDocs, extractManualUpd(d.comment))
                ?: "УПД —"

            // Подзаголовок — госномер авто, введённый инспектором на 1 Этапе.
            // На этом экране он уже точно известен (без госномера 1 Этап не
            // финализируется), поэтому показываем сразу номер, без префикса
            // «Госномер:» — экономит место и читается быстрее.
            val subtitleText = d.vehiclePlate?.takeIf { it.isNotBlank() } ?: "—"

            // Имя поставщика для группировки. Приоритет тот же, что на
            // 1 Этапе (IntakeUpdSelectViewModel): сначала денормализованное
            // имя из УПД, потом справочник — иначе заголовки Этапов разъедутся.
            // `supplier_id` у приёмок на практике пуст, так что основной путь —
            // привязанный документ; его докачивает backfill из init.
            val supplierName = attachedDocs.firstNotNullOfOrNull { doc ->
                doc.supplierName?.takeIf(String::isNotBlank)
                    ?: doc.supplierId?.let { cpById[it]?.name }
            } ?: d.supplierId?.let { cpById[it]?.name }

            val row = Stage2DeliveryRow(
                delivery = d,
                titleText = titleText,
                subtitleText = subtitleText,
                hasDraft = d.id in draftIdSet,
            )
            row to supplierName
        }

        groupByParty(pairs) { it.second }
            .map { group ->
                Stage2DeliveryGroup(group.key, group.displayName, group.rows.map { it.first })
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private companion object {
        /** Статусы приёмки, попадающие в список 2-го Этапа. */
        val STAGE2_STATUSES = listOf("filled")

        /**
         * Строка «УПД: <number>» либо «Примечание: <number>» в комментарии приёмки.
         * Stage1FormViewModel пишет ручной УПД с префиксом «Примечание:» — без него
         * заголовок карточки приёмки без УПД был бы пустым («УПД —»).
         */
        val MANUAL_UPD_REGEX = Regex("(?m)^(?:УПД|Примечание):\\s*(.+)$")

        fun extractManualUpd(comment: String?): String? {
            if (comment.isNullOrBlank()) return null
            return MANUAL_UPD_REGEX.find(comment)?.groupValues?.getOrNull(1)?.trim()
        }
    }
}
