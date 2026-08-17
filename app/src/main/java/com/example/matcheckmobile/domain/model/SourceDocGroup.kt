package com.example.matcheckmobile.domain.model

import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity

/**
 * «Машина» — документы одной загрузки с веб-портала /uploads.
 *
 * Одна карточка «Машина N» у поставщика = один пакет на сервере, из которого
 * может выйти несколько документов: две УПД плюс транспортная накладная в одной
 * пачке фото. Инспектору приезжает ОДНА машина, и приёмка у неё должна быть
 * одна — иначе он оформляет один и тот же рейс несколько раз, а остаток
 * документов висит в «Сегодня» как неоформленный.
 *
 * Группу задаёт сервер полем [RemoteSourceDocumentEntity.groupId]; здесь только
 * правила отображения и слияния. Документ с `groupId == null` — сам себе группа.
 */

/**
 * Приоритет вида документа в группе. УПД первая: она несёт позиции, реквизиты
 * и подрядчика, тогда как транспортная накладная — сопроводительная. От этого
 * порядка зависят заголовок карточки, сквозная нумерация позиций и то, чьи
 * реквизиты выигрывают в [mergeGroupParty].
 */
private fun kindRank(kind: String?): Int = when (kind) {
    "upd" -> 0
    "transport_waybill", "os2_transfer" -> 1
    else -> 2
}

/**
 * Детерминированный порядок документов внутри машины.
 *
 * Детерминированный — обязательное требование, а не удобство: по этому порядку
 * позиции получают сквозной `lineNo`, с которым потом сходятся финансы из УПД
 * (см. finalizeStage1). Плавающий порядок развалил бы цены и НДС.
 *
 * Тай-брейк по `id` в конце: два документа одного вида без номера иначе
 * менялись бы местами между вызовами.
 */
fun sortGroupDocs(docs: List<RemoteSourceDocumentEntity>): List<RemoteSourceDocumentEntity> =
    docs.sortedWith(
        compareBy(
            { kindRank(it.kind) },
            { it.docNumber?.takeIf(String::isNotBlank) ?: "￿" },
            { it.id },
        ),
    )

/**
 * Заголовок карточки для машины: `«УПД 1403, 1404 · Накладная 192»`.
 *
 * Номера сгруппированы по виду, виды разделены «·». Для одиночного документа
 * вырождается ровно в [sourceDocTitle] — экранам не нужно ветвиться.
 *
 * Документ без номера показывается как «—», а не пропускается: инспектор должен
 * видеть, что в машине есть ещё одна бумага, пусть и нераспознанная.
 */
fun sourceDocGroupTitle(docs: List<RemoteSourceDocumentEntity>): String {
    val sorted = sortGroupDocs(docs)
    if (sorted.isEmpty()) return sourceDocTitlePrefix(null)
    // LinkedHashMap: порядок видов = порядок их первого появления в sorted,
    // то есть тот же приоритет, что у kindRank.
    val numbersByPrefix = LinkedHashMap<String, MutableList<String>>()
    for (doc in sorted) {
        val prefix = sourceDocTitlePrefix(doc.kind)
        val number = doc.docNumber?.takeIf(String::isNotBlank) ?: "—"
        numbersByPrefix.getOrPut(prefix) { mutableListOf() }.add(number)
    }
    return numbersByPrefix.entries.joinToString(" · ") { (prefix, numbers) ->
        "$prefix ${numbers.joinToString(", ")}"
    }
}

/**
 * Подпись стороны в строке выбора УПД: «Грузополучатель: …», иначе
 * «Покупатель: …», иначе прочерк.
 *
 * Подрядчика здесь нет намеренно и его нельзя добавлять «до кучи»: на портале
 * колонка «Подрядчик» скрыта из таблиц документов и операций, и строка на
 * планшете расходилась бы с тем, что видит менеджер в той же поставке.
 *
 * Подпись меняется вместе со значением, а не остаётся «Грузополучатель» с
 * чужим содержимым: иначе инспектор прочитал бы графу 6 как графу 4.
 */
fun partyLabel(consigneeName: String?, buyerName: String?): String {
    consigneeName?.takeIf(String::isNotBlank)?.let { return "Грузополучатель: $it" }
    buyerName?.takeIf(String::isNotBlank)?.let { return "Покупатель: $it" }
    return "Грузополучатель: —"
}

/**
 * «3 документа» — русская плюрализация для подписи карточки.
 *
 * 11..14 — исключение из правила по последней цифре: «11 документов», а не
 * «11 документа».
 */
fun docCountLabel(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "документов"
        count % 10 == 1 -> "документ"
        count % 10 in 2..4 -> "документа"
        else -> "документов"
    }
    return "$count $word"
}

/**
 * Реквизиты машины: по каждому полю — первое непустое значение в порядке
 * [sortGroupDocs].
 *
 * Не «с якоря»: внутри одной машины поля расходятся, и расходятся именно как
 * «пусто против значения». В боевых данных транспортная накладная не несёт
 * `contractorId`, а часть УПД не несёт `consigneeName` — взяв всё с первого
 * документа, приёмка уехала бы с прочерком там, где значение известно из
 * соседней бумаги.
 *
 * Конфликта двух непустых значений в боевых данных не встречается (поставщик,
 * МОЛ и дата поставки внутри пакета едины). Если он всё же возникнет, выигрывает
 * первый по порядку — поведение детерминировано, а не «какое попадётся».
 */
data class GroupParty(
    val supplierId: String?,
    val contractorId: String?,
    val recipientMolId: String?,
    val supplierName: String?,
    val consigneeName: String?,
    val createdByUserPhone: String?,
    val siteName: String?,
)

fun mergeGroupParty(docs: List<RemoteSourceDocumentEntity>): GroupParty {
    val sorted = sortGroupDocs(docs)
    fun pick(selector: (RemoteSourceDocumentEntity) -> String?): String? =
        sorted.firstNotNullOfOrNull { selector(it)?.takeIf(String::isNotBlank) }
    return GroupParty(
        supplierId = pick { it.supplierId },
        contractorId = pick { it.contractorId },
        recipientMolId = pick { it.recipientMolId },
        supplierName = pick { it.supplierName },
        consigneeName = pick { it.consigneeName },
        createdByUserPhone = pick { it.createdByUserPhone },
        siteName = pick { it.siteName },
    )
}

/**
 * Ключ группировки для UI и для черновиков: id машины, а для документа без
 * машины — его собственный id.
 */
fun groupKeyOf(doc: RemoteSourceDocumentEntity): String = doc.groupId ?: doc.id

/**
 * Документы, разложенные по машинам: каждая группа отсортирована
 * [sortGroupDocs], сами группы — по своему якорному документу, чтобы порядок
 * строк в списке не «прыгал» между пересборками state.
 */
fun groupDocsByMachine(
    docs: List<RemoteSourceDocumentEntity>,
): List<List<RemoteSourceDocumentEntity>> =
    docs.groupBy(::groupKeyOf)
        .values
        .map(::sortGroupDocs)
        .sortedBy { it.first().id }

/**
 * Ключ черновика, совместимый с записями, сделанными ДО появления группировки.
 *
 * У старого черновика заполнен только `updId`, а строка списка ищет себя по
 * id машины. Без промежуточного шага «updId → groupId его документа» такой
 * черновик после обновления приложения потерял бы свою карточку вместе с
 * бейджем «Начато» и уже снятыми фотографиями.
 *
 * Возвращает null только для черновика вообще без документа (пустая приёмка) —
 * такие показываются отдельным блоком «Созданы вручную».
 */
fun draftGroupKey(
    draftGroupId: String?,
    draftUpdId: String?,
    docsById: Map<String, RemoteSourceDocumentEntity>,
): String? = draftGroupId
    ?: draftUpdId?.let { docsById[it]?.groupId }
    ?: draftUpdId
