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
): List<MaterialDisplayGroup> {
    // Одиночный документ (или его отсутствие) — прежний вид: номер уже стоит в
    // шапке формы, дублировать его в заголовке блока незачем.
    if (docs.size <= 1) {
        return if (materials.isEmpty()) {
            emptyList()
        } else {
            listOf(MaterialDisplayGroup(documentId = docs.firstOrNull()?.id, label = MATERIALS, materials = materials))
        }
    }

    val known = docs.map { it.id }.toSet()
    val groups = docs.mapNotNull { doc ->
        // filter сохраняет исходный порядок — внутри блока позиции идут так же,
        // как в общем списке, и сверять их с УПД можно построчно.
        val own = materials.filter { it.sourceDocumentId == doc.id }
        if (own.isEmpty()) return@mapNotNull null
        MaterialDisplayGroup(
            documentId = doc.id,
            // «без номера» вместо пустоты: у документа номер может не
            // распознаться, и заголовок «Материалы null» инспектору ничего не
            // объясняет.
            label = "$MATERIALS ${doc.number?.takeIf(String::isNotBlank) ?: "без номера"}",
            materials = own,
        )
    }

    // Всё, что не принадлежит ни одному документу машины: строки инспектора
    // (sourceDocumentId == null) и позиции документа, который из машины ушёл.
    // Терять их нельзя — инспектор их видел и, возможно, правил.
    val orphans = materials.filter { it.sourceDocumentId == null || it.sourceDocumentId !in known }
    if (orphans.isEmpty()) return groups
    return groups + MaterialDisplayGroup(documentId = null, label = MATERIALS, materials = orphans)
}
