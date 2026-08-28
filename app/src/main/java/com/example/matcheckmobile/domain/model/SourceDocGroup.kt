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
 * Заголовок машины для экранов, где документы уже ПРИВЯЗАНЫ к приёмке или
 * отгрузке: списки 2 Этапа, формы 2 Этапа, Архив.
 *
 * Отличие от [sourceDocGroupTitle] только во входе. На 1 Этапе документы машины
 * лежат в Room целиком, здесь же их приходится собирать по
 * `sourceDocumentIdsJson`, и часть может отсутствовать: сервер не отдаёт УПД,
 * уже привязанную к операции, поэтому до дотяжки backfill'ом кэш пуст. У ручной
 * приёмки документов нет вовсе, а номер лежит строкой «УПД: …» в комментарии.
 *
 * Порядок запасных путей повторяет прежний код списков дословно — номера
 * документов, затем ручная метка, затем ничего. Менять его нельзя: ручная метка
 * должна проигрывать реальным документам, иначе приёмка с распознанной УПД
 * подписалась бы текстом, который инспектор набрал руками до привязки.
 *
 * Возвращает null, когда сказать нечего вообще: у приёмки это «УПД —», у
 * отгрузки «Без УПД», и выбор остаётся за экраном.
 *
 * Заводить эту функцию отдельно от списков пришлось потому, что каждый из
 * четырёх экранов держал СВОЮ копию правила, и все четыре разошлись с 1 Этапом:
 * один префикс на всю карточку по первому документу (накладная подписывалась
 * как «УПД»), обрезка списка на втором номере и молчаливая потеря документов
 * без номера.
 */
fun attachedDocsGroupTitle(
    docs: List<RemoteSourceDocumentEntity>,
    manualUpd: String?,
): String? = when {
    docs.any { !it.docNumber.isNullOrBlank() } -> sourceDocGroupTitle(docs)
    !manualUpd.isNullOrBlank() -> "УПД $manualUpd"
    // Документы есть, но ни одного номера. Показываем их всё равно: «УПД —, —»
    // сообщает инспектору, что бумаг в машине две, а нераспознанный номер —
    // повод открыть карточку, а не причина скрыть документ.
    docs.isNotEmpty() -> sourceDocGroupTitle(docs)
    else -> null
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

/**
 * Документы машины, которые НЕ вошли в уже созданную приёмку.
 *
 * Зачем. Состав приёмки фиксируется на 1 Этапе, и сервер требует его целиком:
 * подмножество отклоняется (`incomplete_group`). Но документ может доехать
 * ПОЗЖЕ — распознавание сорвалось, менеджер разобрал бумагу вручную, повтор
 * прошёл успешно, — и тогда он появляется в машине уже после оформления. На
 * 2 Этапе подтверждается СУЩЕСТВУЮЩАЯ приёмка, её состав не меняется, поэтому
 * такой документ не будет учтён, а МОЛ об этом ничего не узнает: материалов по
 * нему в приёмке просто нет.
 *
 * Функция только СЧИТАЕТ расхождение — ни приёмку, ни документы она не трогает.
 * Решение остаётся за человеком: молча дописать документ в подтверждаемую
 * приёмку нельзя, это меняет принятое количество материалов задним числом.
 *
 * Пустой [groupDocs] трактуется как «локальная копия ещё не доехала», а не как
 * «документы исчезли»: та же осторожность, что и в сверке 1 Этапа. Тревожить
 * МОЛ на основании неполной синхронизации хуже, чем промолчать.
 *
 * Обратное направление — документ приёмки, которого нет в машине, — намеренно
 * НЕ возвращается. На планшете он мог пропасть и по безобидной причине: ушёл
 * из выдачи по предикату видимости или его вычистила локальная база. Приёмка
 * при этом остаётся корректной, а ложная тревога у МОЛ дороже молчания.
 *
 * @param acceptedIds документы, привязанные к приёмке (delivery.sourceDocumentIds).
 * @param groupDocs все документы машины по данным локальной базы.
 */
fun lateGroupDocuments(
    acceptedIds: List<String>,
    groupDocs: List<RemoteSourceDocumentEntity>,
): List<RemoteSourceDocumentEntity> {
    if (acceptedIds.isEmpty()) return emptyList()
    if (groupDocs.isEmpty()) return emptyList()
    val accepted = acceptedIds.toSet()
    return sortGroupDocs(groupDocs.filterNot { it.id in accepted })
}
