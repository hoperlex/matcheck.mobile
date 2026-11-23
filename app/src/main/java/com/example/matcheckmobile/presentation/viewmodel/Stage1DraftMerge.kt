package com.example.matcheckmobile.presentation.viewmodel

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.repository.Stage1DraftState
import com.example.matcheckmobile.domain.model.draftGroupKey
import com.example.matcheckmobile.domain.model.groupKeyOf
import com.example.matcheckmobile.domain.model.sortGroupDocs
import com.example.matcheckmobile.presentation.components.MaterialDraft
import kotlinx.serialization.Serializable

/**
 * Черновики 1 Этапа приёмки и машины, которые меняются на портале.
 *
 * Менеджер может объединить две машины в одну или разъединить объединённую.
 * Если инспектор уже начинал оформлять их на планшете, его работа лежит в
 * черновиках, привязанных к прежнему составу. Правило одно: работа инспектора
 * не пропадает, а переезжает туда, где её документы сейчас.
 *
 *  - Объединили — черновики машины СЛИВАЮТСЯ: фото складываются, строки
 *    материалов совмещаются по происхождению, а значения полей, которые
 *    разошлись (госномер, тип машины, тексты), не склеиваются — инспектор
 *    выбирает основной вариант, остальные хранятся в [DraftVariant].
 *  - Разъединили — строки ушедших документов (с правками) переезжают в
 *    черновик их новой машины; фото остаются в исходном.
 *  - Документа сейчас нет в локальной базе (сервер временно скрыл его на
 *    переразборе, неполная машина) — его строки ОТКЛАДЫВАЮТСЯ, а не теряются:
 *    в форму и в отправку не идут, вернётся документ — вернутся и строки.
 *
 * Всё здесь — чистые функции: правила проверяются тестом без Room и ViewModel.
 */

/**
 * Значения полей одного из слитых черновиков. Хранятся, пока черновик жив:
 * выбор «основного» не стирает чужой ввод.
 */
@Serializable
data class DraftVariant(
    val draftId: String,
    val updatedAt: Long,
    val licensePlate: String = "",
    val vehicleTypeCode: String? = null,
    val commentText: String = "",
    val manualUpdText: String = "",
    /** Этот вариант инспектор выбрал основным. */
    val chosen: Boolean = false,
)

/** Варианты ждут выбора инспектора: конфликт есть, основной не выбран. */
fun List<DraftVariant>.awaitingChoice(): Boolean = isNotEmpty() && none { it.chosen }

private fun Stage1DraftState.variant() = DraftVariant(
    draftId = localDraftId,
    updatedAt = updatedAt,
    licensePlate = licensePlate,
    vehicleTypeCode = vehicleTypeCode,
    commentText = commentText,
    manualUpdText = manualUpdText,
)

/**
 * Ключ строки по происхождению: документ и его строка. Строки, у которых нет
 * обоих полей (свои строки инспектора), ключа не имеют и переходят все.
 *
 * Строки РАЗНЫХ документов не совпадают никогда, даже при одинаковых
 * названиях: количества и финансы обязаны сохранить происхождение.
 */
private fun rowKey(m: MaterialDraft): String? {
    val doc = m.sourceDocumentId ?: return null
    val item = m.sourceDocumentItemId ?: return null
    return "$doc|$item"
}

/**
 * Склейка строк нескольких черновиков. Совпавшая по происхождению строка
 * берётся из более свежего черновика; порядок — как в первом черновике, затем
 * новые строки остальных.
 */
private fun mergeRows(ordered: List<Pair<Long, List<MaterialDraft>>>): List<MaterialDraft> {
    val winner = HashMap<String, MaterialDraft>()
    for ((_, rows) in ordered.sortedByDescending { it.first }) {
        for (m in rows) rowKey(m)?.let { winner.putIfAbsent(it, m) }
    }
    val out = mutableListOf<MaterialDraft>()
    val emitted = HashSet<String>()
    for ((_, rows) in ordered) {
        for (m in rows) {
            val key = rowKey(m)
            when {
                key == null -> out += m
                emitted.add(key) -> out += winner.getValue(key)
            }
        }
    }
    return out
}

/**
 * Слить черновики ОДНОЙ машины в [base] (его id и якорь сохраняются).
 *
 * Поля: одинаковые — одно значение; одно пустое — берётся непустое; разные —
 * конфликт: в черновике остаётся значение [base] (или более свежее непустое),
 * а все варианты записываются в mergedVariants и ждут выбора инспектора.
 * Флажки «Транзит»/«ОС» не конфликтуют: отмеченный в любом черновике остаётся
 * отмеченным.
 */
fun mergeDrafts(base: Stage1DraftState, others: List<Stage1DraftState>): Stage1DraftState {
    if (others.isEmpty()) return base
    val all = listOf(base) + others.sortedByDescending { it.updatedAt }
    val byFresh = all.sortedByDescending { it.updatedAt }

    fun pick(field: (Stage1DraftState) -> String?): Pair<String?, Boolean> {
        val values = all.mapNotNull { field(it)?.takeIf(String::isNotBlank) }.distinct()
        val chosen = field(base)?.takeIf(String::isNotBlank)
            ?: byFresh.firstNotNullOfOrNull { field(it)?.takeIf(String::isNotBlank) }
        return chosen to (values.size > 1)
    }

    val (plate, plateConflict) = pick { it.licensePlate }
    val (vehicle, vehicleConflict) = pick { it.vehicleTypeCode }
    val (comment, commentConflict) = pick { it.commentText }
    val (manual, manualConflict) = pick { it.manualUpdText }
    val conflict = plateConflict || vehicleConflict || commentConflict || manualConflict

    // Варианты: уже хранившиеся (прошлые слияния) плюс слитые сейчас. Новый
    // конфликт снимает прежний выбор — решать заново должен человек.
    val previous = all.flatMap { it.mergedVariants }
    val variants = if (conflict) {
        (previous + all.map { it.variant() })
            .distinctBy { listOf(it.licensePlate, it.vehicleTypeCode, it.commentText, it.manualUpdText) }
            .map { it.copy(chosen = false) }
    } else {
        previous.distinctBy { it.draftId }
    }

    return base.copy(
        loadedDocIds = all.flatMap { it.loadedDocIds }.distinct(),
        documentPhotoPaths = all.flatMap { it.documentPhotoPaths }.distinct(),
        cargoPhotoPaths = all.flatMap { it.cargoPhotoPaths }.distinct(),
        vehicleTypeCode = vehicle,
        materials = mergeRows(all.map { it.updatedAt to it.materials }),
        parkedMaterials = mergeRows(all.map { it.updatedAt to it.parkedMaterials }),
        commentText = comment.orEmpty(),
        licensePlate = plate.orEmpty(),
        manualUpdText = manual.orEmpty(),
        inTransit = all.any { it.inTransit },
        isAssets = all.any { it.isAssets },
        mergedVariants = variants,
        createdAt = all.minOf { it.createdAt },
        updatedAt = all.maxOf { it.updatedAt },
    )
}

/**
 * Применить выбранный инспектором вариант: его НЕПУСТЫЕ значения становятся
 * основными, пустые не стирают известное. Варианты остаются в черновике.
 */
fun Stage1DraftState.chooseVariant(draftId: String): Stage1DraftState {
    val v = mergedVariants.firstOrNull { it.draftId == draftId } ?: return this
    return copy(
        licensePlate = v.licensePlate.ifBlank { licensePlate },
        vehicleTypeCode = v.vehicleTypeCode ?: vehicleTypeCode,
        commentText = v.commentText.ifBlank { commentText },
        manualUpdText = v.manualUpdText.ifBlank { manualUpdText },
        mergedVariants = mergedVariants.map { it.copy(chosen = it.draftId == draftId) },
    )
}

/** Черновик, в котором не осталось ничего: ни фото, ни строк, ни ввода. */
fun Stage1DraftState.isStructurallyEmpty(): Boolean =
    documentPhotoPaths.isEmpty() && cargoPhotoPaths.isEmpty() &&
        materials.isEmpty() && parkedMaterials.isEmpty() &&
        commentText.isBlank() && licensePlate.isBlank() && manualUpdText.isBlank() &&
        vehicleTypeCode == null && !inTransit && !isAssets && mergedVariants.isEmpty()

/**
 * Есть ли в форме работа инспектора, ради которой черновик хранится БЕЗ фото.
 *
 * Раньше черновик без фото удалялся автосохранением целиком — вместе с
 * правками количества, своими строками и текстами. Теперь пустым считается
 * только черновик, в котором нет ничего сверх того, что форма и так заполнит
 * из документов: материалы совпадают с исходными ([pristine]), строк в
 * отложенных нет, тексты и тип машины не заданы, флажки не отмечены, выбора
 * между вариантами не было.
 */
fun hasDraftWork(
    materials: List<MaterialDraft>,
    pristine: List<MaterialDraft>,
    parked: List<MaterialDraft>,
    commentText: String,
    licensePlate: String,
    manualUpdText: String,
    vehicleTypeCode: String?,
    inTransit: Boolean,
    isAssets: Boolean,
    mergedVariants: List<DraftVariant>,
): Boolean = materials != pristine || parked.isNotEmpty() ||
    commentText.isNotBlank() || licensePlate.isNotBlank() || manualUpdText.isNotBlank() ||
    vehicleTypeCode != null || inTransit || isAssets || mergedVariants.isNotEmpty()

/**
 * Что сделать с черновиками при открытии машины.
 *
 * [result] — черновик машины, с которым откроется форма (null — черновиков
 * нет и чужих строк не нашлось: форма начнёт с нуля). [delete] — поглощённые
 * черновики. [save] — всё, что нужно записать: [result], черновики, отдавшие
 * или получившие строки, новые черновики других машин. Применять ОДНОЙ
 * транзакцией (Stage1DraftRepository.applyPlan).
 */
data class DraftPlan(
    val result: Stage1DraftState?,
    val delete: List<String>,
    val save: List<Stage1DraftState>,
) {
    val isNoop: Boolean get() = delete.isEmpty() && save.isEmpty()
}

/**
 * Свести черновики к актуальному составу машины [targetDocs].
 *
 * 1. Черновики этой машины (по документам, [draftGroupKey]) сливаются в один:
 *    основа — открытый инспектором ([preferredId]), иначе самый свежий.
 * 2. Строки документов этой машины, лежащие в черновиках ДРУГИХ машин
 *    (машину разъединили, а строки остались у соседа), забираются сюда.
 * 3. Строки документов, которых в машине больше нет:
 *    - документ есть в локальной базе (в другой машине) — уезжают в черновик
 *      той машины (существующий или новый);
 *    - документа нет — откладываются (parkedMaterials).
 *    Отложенные строки документов, вернувшихся в эту машину, возвращаются.
 * 4. Черновик, у которого после этого не осталось ничего, удаляется.
 *
 * @param drafts все черновики 1 Этапа на планшете (приёмка).
 * @param docsById документы из локальной базы — как минимум документы машины
 *   и всё, на что ссылаются черновики.
 */
fun planMachineDrafts(
    targetKey: String,
    targetDocs: List<RemoteSourceDocumentEntity>,
    drafts: List<Stage1DraftState>,
    docsById: Map<String, RemoteSourceDocumentEntity>,
    preferredId: String?,
    newDraftId: () -> String,
    now: Long,
): DraftPlan {
    val targetDocIds = targetDocs.map { it.id }.toSet()
    fun keyOf(d: Stage1DraftState) = draftGroupKey(d.groupId, d.updId, docsById, d.loadedDocIds)

    val members = drafts.filter { keyOf(it) == targetKey }
    val memberIds = members.map { it.localDraftId }.toSet()
    val base = members.firstOrNull { it.localDraftId == preferredId } ?: members.maxByOrNull { it.updatedAt }
    val changed = LinkedHashMap<String, Stage1DraftState>()
    val delete = mutableListOf<String>()

    var target: Stage1DraftState? = base?.let { b ->
        delete += members.filter { it !== b }.map { it.localDraftId }
        mergeDrafts(b, members.filter { it !== b })
    }

    // 2. Строки этой машины у соседей.
    val incoming = mutableListOf<Pair<Long, List<MaterialDraft>>>()
    for (d in drafts) {
        if (d.localDraftId in memberIds) continue
        val (mineM, restM) = d.materials.partition { it.sourceDocumentId in targetDocIds }
        val (mineP, restP) = d.parkedMaterials.partition { it.sourceDocumentId in targetDocIds }
        if (mineM.isEmpty() && mineP.isEmpty()) continue
        incoming += d.updatedAt to (mineM + mineP)
        changed[d.localDraftId] = d.copy(materials = restM, parkedMaterials = restP, updatedAt = now)
    }
    if (incoming.isNotEmpty()) {
        val rows = mergeRows(incoming)
        target = target
            ?.let { t -> t.copy(materials = mergeRows(listOf(t.updatedAt to t.materials, now to rows))) }
            ?: newMachineDraft(newDraftId(), targetDocs, rows, now)
    }

    // 3. Строки документов, которых в машине нет.
    val exports = LinkedHashMap<String, MutableList<MaterialDraft>>()
    target = target?.let { t ->
        val keep = mutableListOf<MaterialDraft>()
        val parked = mutableListOf<MaterialDraft>()
        for (m in t.materials + t.parkedMaterials) {
            val docId = m.sourceDocumentId
            val elsewhere = docId?.let(docsById::get)?.let(::groupKeyOf)
            when {
                docId == null || docId in targetDocIds -> keep += m
                // Документ числится в этой же машине, но в составе его нет —
                // локальная база рассогласована. Строку не выносим: лучше
                // оставить её здесь, чем завести черновик-двойник машины.
                elsewhere == targetKey -> keep += m
                elsewhere != null -> exports.getOrPut(elsewhere) { mutableListOf() } += m
                else -> parked += m
            }
        }
        // Вернувшиеся из отложенных строки встают в конец: при открытии формы
        // mergeGroupMaterials сопоставит их с позициями документа. Документы,
        // которых в машине нет, перестают считаться «загруженными»: вернётся
        // такой документ — форма увидит его как добавленный, перечитает его
        // позиции и сообщит инспектору.
        t.copy(
            materials = keep,
            parkedMaterials = parked,
            loadedDocIds = t.loadedDocIds.filter { it in targetDocIds },
        )
    }
    for ((key, rows) in exports) {
        val existing = changed.values.firstOrNull { keyOf(it) == key }
            ?: drafts.firstOrNull { it.localDraftId !in memberIds && keyOf(it) == key }
        if (existing != null) {
            val cur = changed[existing.localDraftId] ?: existing
            changed[existing.localDraftId] = cur.copy(
                materials = mergeRows(listOf(cur.updatedAt to cur.materials, now to rows)),
                updatedAt = now,
            )
        } else {
            val docs = sortGroupDocs(docsById.values.filter { groupKeyOf(it) == key })
            if (docs.isNotEmpty()) {
                val created = newMachineDraft(newDraftId(), docs, rows, now)
                changed[created.localDraftId] = created
            }
        }
    }

    // 4. Опустевшие соседи удаляются, остальные записываются.
    val save = mutableListOf<Stage1DraftState>()
    for (d in changed.values) {
        if (d.isStructurallyEmpty()) delete += d.localDraftId else save += d
    }
    val result = target?.let { t ->
        // Состав и ключ — по текущей машине: сохранённый groupId мог указывать
        // на машину, которой больше нет.
        t.copy(groupId = targetDocs.firstOrNull()?.groupId ?: t.groupId)
    }
    if (result != null && (base == null || result != base || delete.isNotEmpty() || save.isNotEmpty())) {
        save.add(0, result)
    }
    return DraftPlan(result = result, delete = delete.distinct(), save = save)
}

/**
 * Черновик машины, которому достались строки соседа. Фото нет — его хранит
 * правило «есть работа» (строки с правками). [Stage1DraftState.loadedDocIds] —
 * только документы пришедших строк: остальные позиции машины форма допишет
 * при открытии и честно сообщит об этом.
 */
private fun newMachineDraft(
    id: String,
    docs: List<RemoteSourceDocumentEntity>,
    rows: List<MaterialDraft>,
    now: Long,
): Stage1DraftState {
    val sorted = sortGroupDocs(docs)
    val withRows = rows.mapNotNull { it.sourceDocumentId }.toSet()
    return Stage1DraftState(
        localDraftId = id,
        updId = sorted.firstOrNull()?.id,
        groupId = sorted.firstOrNull()?.groupId,
        loadedDocIds = sorted.map { it.id }.filter { it in withRows },
        loadedGroupRevision = sorted.firstOrNull()?.groupRevision,
        documentPhotoPaths = emptyList(),
        cargoPhotoPaths = emptyList(),
        vehicleTypeCode = null,
        materials = rows,
        commentText = "",
        licensePlate = "",
        manualUpdText = "",
        createdAt = now,
        updatedAt = now,
    )
}
