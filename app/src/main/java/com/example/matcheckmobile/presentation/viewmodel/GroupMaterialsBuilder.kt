package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo

/**
 * Сборка позиций формы 1 Этапа из документов машины.
 *
 * Вынесено из обеих форм (приёмка и отгрузка) отдельной чистой функцией по двум
 * причинам: правило одно на оба направления, и его нужно проверять тестом без
 * поднятия Room и ViewModel.
 */
data class GroupMaterials(
    val materials: List<MaterialDraft>,
    val financials: List<UpdItemFinancials>,
    val loadInfo: VehicleLoadInfo?,
)

/**
 * @param items позиции всех документов машины В ПОРЯДКЕ документов
 *   (sortGroupDocs), парой «id документа → позиция».
 *
 * Нумерация lineNo — СКВОЗНАЯ по всей машине (`idx + 1`), а не исходный номер
 * строки документа. Иначе у второй УПД строки снова начинались бы с единицы, и
 * при финализации (`lineNo = idx + 1` в finalizeStage1) цены и НДС уехали бы
 * к чужим позициям.
 *
 * Каждая позиция несёт своё происхождение: по нему веб-портал раскладывает
 * приёмку по документам, а форма умеет точечно дописать или убрать позиции при
 * изменении состава машины.
 */
fun buildGroupMaterials(
    items: List<Pair<String, RemoteSourceDocumentItemEntity>>,
): GroupMaterials {
    val materials = items.map { (docId, item) ->
        MaterialDraft(
            name = item.nameRaw,
            qty = item.qty,
            unit = item.unit,
            sourceDocumentId = docId,
            sourceDocumentItemId = item.id,
        )
    }
    val financials = items.mapIndexed { idx, (_, item) ->
        UpdItemFinancials(
            lineNo = idx + 1,
            price = item.price,
            vatRate = item.vatRate,
            vatSum = item.vatSum,
        )
    }
    return GroupMaterials(
        materials = materials,
        financials = financials,
        loadInfo = buildLoadInfo(items.map { it.second }),
    )
}

/**
 * Слияние текущих строк формы с АКТУАЛЬНЫМ содержимым документов машины.
 *
 * Делает три вещи разом, и все три обязательны:
 *  - строки исчезнувших из машины документов убирает;
 *  - строки живых документов ПЕРЕЧИТЫВАЕТ. Повторный разбор на сервере удаляет
 *    и создаёт позиции заново с новыми id (worker: `DELETE ... WHERE
 *    source_document_id`), поэтому сопоставление идёт сначала по
 *    sourceDocumentItemId, а при промахе — по названию и единице. Без этого
 *    форма осталась бы со старыми количествами и мёртвыми ссылками, а диалог
 *    «состав изменился» не показал бы ничего: набор документов-то прежний;
 *  - позиции документов, которых в форме ещё не было, дописывает в конец.
 *
 * Введённое инспектором не трогается: его собственные строки
 * (sourceDocumentId == null) остаются как есть, у сопоставленных сохраняются
 * правки количества — обновляется только привязка к строке документа.
 *
 * @param docItems позиции документов машины В ПОРЯДКЕ документов (sortGroupDocs).
 */
fun mergeGroupMaterials(
    current: List<MaterialDraft>,
    docItems: List<Pair<String, List<RemoteSourceDocumentItemEntity>>>,
): GroupMaterials {
    val itemsByDoc = docItems.toMap()
    // Расходуется однократно, чтобы две одинаковые строки УПД не схлопнулись в
    // одну при сопоставлении по названию.
    val consumed = mutableSetOf<String>()

    val kept = current.mapNotNull { m ->
        val docId = m.sourceDocumentId ?: return@mapNotNull m // строка инспектора
        val items = itemsByDoc[docId] ?: return@mapNotNull null // документ ушёл из машины
        val match = items.firstOrNull { it.id == m.sourceDocumentItemId && it.id !in consumed }
            ?: items.firstOrNull { it.id !in consumed && it.nameRaw == m.name && it.unit == m.unit }
        // Позиции больше нет в документе — строку убираем: иначе инспектор
        // принял бы материал, которого в УПД уже не значится.
        match?.let {
            consumed += it.id
            m.copy(sourceDocumentItemId = it.id)
        }
    }

    val appended = docItems.flatMap { (docId, items) ->
        items.filter { it.id !in consumed }.map { docId to it }
    }

    val merged = kept + appended.map { (docId, item) ->
        MaterialDraft(
            name = item.nameRaw,
            qty = item.qty,
            unit = item.unit,
            sourceDocumentId = docId,
            sourceDocumentItemId = item.id,
        )
    }

    // Финансы пересобираем по НОВОЙ нумерации: lineNo сквозной по итоговому
    // списку, и старая раскладка после вставки/удаления строк указывала бы не на
    // те позиции.
    val itemsById = docItems.associate { (docId, items) -> docId to items.associateBy { it.id } }
    val financials = merged.mapIndexedNotNull { idx, m ->
        val item = m.sourceDocumentId?.let { itemsById[it]?.get(m.sourceDocumentItemId) }
        item?.let {
            UpdItemFinancials(
                lineNo = idx + 1,
                price = it.price,
                vatRate = it.vatRate,
                vatSum = it.vatSum,
            )
        }
    }
    return GroupMaterials(
        materials = merged,
        financials = financials,
        loadInfo = buildLoadInfo(docItems.flatMap { it.second }),
    )
}

/**
 * Датчик загрузки машины по позициям всех её документов.
 *
 * На веб-портале volumeM3 и massKg у позиции хранятся ПО ЕДИНИЦЕ материала,
 * поэтому суммируем как `qty * value`. Отдельно трекаем hasVolume/hasMass:
 * если данных нет ни у одной позиции, возвращаем null, и gauge рисуется как
 * «нет данных» (см. apps/web/.../VehicleFillGauge.tsx).
 */
fun buildLoadInfo(items: List<RemoteSourceDocumentItemEntity>): VehicleLoadInfo? {
    var volume = 0.0
    var massKg = 0.0
    var hasVolume = false
    var hasMass = false
    for (item in items) {
        val qty = item.qty.toDoubleOrNull() ?: 0.0
        item.volumeM3?.toDoubleOrNull()?.let {
            volume += it * qty
            hasVolume = true
        }
        item.massKg?.toDoubleOrNull()?.let {
            massKg += it * qty
            hasMass = true
        }
    }
    return if (hasVolume || hasMass) {
        VehicleLoadInfo(
            totalVolumeM3 = if (hasVolume) volume else null,
            totalMassT = if (hasMass) massKg / 1000.0 else null,
        )
    } else {
        null
    }
}
