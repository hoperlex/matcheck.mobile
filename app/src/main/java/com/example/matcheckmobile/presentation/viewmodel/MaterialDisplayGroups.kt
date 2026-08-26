package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.presentation.components.MaterialDraft

/**
 * Разбивка материалов машины по документам — ТОЛЬКО для показа.
 *
 * Зачем отдельной чистой функцией, а не фильтром по месту. Машина из двух УПД
 * даёт один список позиций, и инспектор видел «Материалы (3)» без всякого
 * указания, что к какой накладной относится, — а принимает он машину именно по
 * документам.
 *
 * Почему разбивка не должна ничего менять. finalizeStage1 сначала отбрасывает
 * пустые строки и лишь потом нумерует остаток (`lineNo = idx + 1`), доставая по
 * этому номеру цену и НДС из materialFinancials. То есть соответствие
 * «позиция ↔ деньги» держится на точном СОСТАВЕ и ПОРЯДКЕ единого списка.
 * Перестановка или фильтрация, записанная обратно в состояние, увела бы цены к
 * чужим позициям — и увела бы молча, без единой ошибки.
 *
 * Поэтому здесь только чтение: функция возвращает подсписки для экрана, а
 * state.materials остаётся ровно таким, каким был.
 */

/** Документ машины: id и его номер для заголовка. */
data class GroupDocumentLabel(
    val id: String,
    /** null — номер не распознан; заголовок в этом случае не «null», см. ниже. */
    val number: String?,
)

/** Один блок «Материалы …» на экране формы. */
data class MaterialDisplayGroup(
    /** Документ-источник; null — строки, внесённые инспектором вручную. */
    val documentId: String?,
    /** Готовый заголовок блока; счётчик в скобках дорисовывает MaterialsField. */
    val label: String,
    val materials: List<MaterialDraft>,
)

/**
 * Тот же блок, но для РЕДАКТИРУЕМОГО списка 2 Этапа: вместо самих позиций —
 * их индексы в исходном `state.materials`.
 *
 * Почему индексы, а не подсписок. На 1 Этапе материалы только читаются, и
 * подсписка достаточно. На 2 Этапе инспектор правит и удаляет строки, а
 * `EditableMaterialsInlineList` сообщает об этом позицией в ПЕРЕДАННОМ ему
 * списке. Отдай мы подсписок — правка второй строки второго блока ушла бы во
 * вторую строку общего списка, то есть в чужую позицию, и молча: ни ошибки, ни
 * расхождения на экране. Индексы позволяют экрану вернуть номер обратно в
 * систему координат `state.materials`.
 */
data class MaterialDisplayIndexGroup(
    val documentId: String?,
    val label: String,
    /** Индексы в исходном `state.materials`, в исходном порядке. */
    val indexes: List<Int>,
)

private const val MATERIALS = "Материалы"

/**
 * @param materials позиции формы В ИСХОДНОМ порядке (state.materials).
 * @param docs документы машины в порядке sortGroupDocs.
 *
 * Возвращает блоки в порядке документов; в конце — блок позиций, не привязанных
 * ни к одному документу машины. Пустые блоки не возвращаются вовсе: «Материалы
 * УТ-2217 (0)» ничего не сообщает, а место занимает.
 */
fun buildMaterialDisplayGroups(
    materials: List<MaterialDraft>,
    docs: List<GroupDocumentLabel>,
): List<MaterialDisplayGroup> =
    // Через индексную версию, а не своим проходом: правило разбивки обязано
    // быть ОДНИМ на оба этапа. Разъедься они — инспектор увидел бы на 1 и на
    // 2 Этапе разный состав блоков у одной и той же машины.
    buildMaterialDisplayIndexGroups(materials, docs).map { group ->
        MaterialDisplayGroup(
            documentId = group.documentId,
            label = group.label,
            materials = group.indexes.map(materials::get),
        )
    }

/**
 * Разбивка в индексах исходного списка — см. [MaterialDisplayIndexGroup].
 *
 * @param materials позиции формы В ИСХОДНОМ порядке (state.materials).
 * @param docs документы машины в порядке sortGroupDocs.
 */
fun buildMaterialDisplayIndexGroups(
    materials: List<MaterialDraft>,
    docs: List<GroupDocumentLabel>,
): List<MaterialDisplayIndexGroup> {
    // Одиночный документ (или его отсутствие) — прежний вид: номер уже стоит в
    // шапке формы, дублировать его в заголовке блока незачем.
    if (docs.size <= 1) {
        return if (materials.isEmpty()) {
            emptyList()
        } else {
            listOf(
                MaterialDisplayIndexGroup(
                    documentId = docs.firstOrNull()?.id,
                    label = MATERIALS,
                    indexes = materials.indices.toList(),
                ),
            )
        }
    }

    val known = docs.map { it.id }.toSet()
    val groups = docs.mapNotNull { doc ->
        // filter по indices сохраняет исходный порядок — внутри блока позиции
        // идут так же, как в общем списке, и сверять их с УПД можно построчно.
        val own = materials.indices.filter { materials[it].sourceDocumentId == doc.id }
        if (own.isEmpty()) return@mapNotNull null
        MaterialDisplayIndexGroup(
            documentId = doc.id,
            // «без номера» вместо пустоты: у документа номер может не
            // распознаться, и заголовок «Материалы null» инспектору ничего не
            // объясняет.
            label = "$MATERIALS ${doc.number?.takeIf(String::isNotBlank) ?: "без номера"}",
            indexes = own,
        )
    }

    // Всё, что не принадлежит ни одному документу машины: строки инспектора
    // (sourceDocumentId == null) и позиции документа, который из машины ушёл.
    // Терять их нельзя — инспектор их видел и, возможно, правил.
    val orphans = materials.indices.filter {
        val docId = materials[it].sourceDocumentId
        docId == null || docId !in known
    }
    if (orphans.isEmpty()) return groups
    return groups + MaterialDisplayIndexGroup(documentId = null, label = MATERIALS, indexes = orphans)
}
