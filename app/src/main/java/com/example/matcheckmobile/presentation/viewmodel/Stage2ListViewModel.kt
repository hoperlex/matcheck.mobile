package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers
import com.example.matcheckmobile.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Одна строка в списке 2 Этапа — приёмка, ожидающая подтверждения МОЛ.
 * [updSummary] сводит номера всех привязанных УПД в короткую строку: одна
 * УПД → её номер, несколько → «№…, №…», много → «№…, №… +N».
 */
data class Stage2DeliveryRow(
    val delivery: RemoteDeliveryEntity,
    val updSummary: String,
    val supplierName: String?,
)

/** Группа приёмок по подрядчику — структура зеркалит [IntakeUpdGroup] на 1 Этапе. */
data class Stage2DeliveryGroup(
    val contractorName: String,
    val rows: List<Stage2DeliveryRow>,
)

private const val UNKNOWN_CONTRACTOR_LABEL = "Подрядчик не указан"

/**
 * Список приёмок, ожидающих 2 Этап (статус `filled` — «Оформлена»).
 * Подтверждение МОЛ переводит их в `confirmed_mol`, после чего они уходят
 * из этого списка. UI показывает группировку по подрядчику с раскрытием —
 * так же, как «Выбор УПД для приёмки» на 1 Этапе.
 */
class Stage2ListViewModel(container: AppContainer) : ViewModel() {

    val groups: StateFlow<List<Stage2DeliveryGroup>> = combine(
        container.deliveryRepository.observeByStatus("filled"),
        container.database.remoteSourceDocumentDao().observeAll(),
        container.database.remoteCounterpartyDao().observeAll(),
    ) { deliveries, sourceDocs, counterparties ->
        val cpById = counterparties.associateBy { it.id }
        val docById = sourceDocs.associateBy { it.id }

        // Для каждой приёмки готовим row + имя подрядчика для группировки.
        val pairs = deliveries.map { d ->
            val attachedIds = RemoteMappers.decodeIdList(d.sourceDocumentIdsJson)
            val attachedDocs = attachedIds.mapNotNull { docById[it] }

            val updSummary = buildUpdSummary(attachedDocs.mapNotNull { it.docNumber })
            val supplierName = attachedDocs.firstOrNull()?.let { doc ->
                doc.supplierName ?: doc.supplierId?.let { id -> cpById[id]?.name }
            } ?: d.supplierId?.let { cpById[it]?.name }

            val contractorName = d.contractorId?.let { cpById[it]?.name }
                ?: attachedDocs.firstOrNull()?.contractorName

            val row = Stage2DeliveryRow(
                delivery = d,
                updSummary = updSummary,
                supplierName = supplierName,
            )
            val groupKey = contractorName?.takeIf { it.isNotBlank() } ?: UNKNOWN_CONTRACTOR_LABEL
            row to groupKey
        }

        // Группируем + «Подрядчик не указан» сортируем в конец, остальные — по алфавиту.
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

        fun buildUpdSummary(numbers: List<String>): String {
            if (numbers.isEmpty()) return "—"
            if (numbers.size <= UPD_SUMMARY_MAX_INLINE) return numbers.joinToString(", ")
            val head = numbers.take(UPD_SUMMARY_MAX_INLINE).joinToString(", ")
            return "$head +${numbers.size - UPD_SUMMARY_MAX_INLINE}"
        }
    }
}
