package com.example.matcheckmobile.presentation.viewmodel

/**
 * Группировка списков УПД и приёмок/отгрузок по контрагенту — общий код для
 * четырёх экранов (1 и 2 Этап × приёмка и отгрузка). Раньше эта логика была
 * скопирована в каждую ViewModel вместе с константой «не указан».
 *
 * Ключ группировки — **нормализованное имя**, а не id справочника: в боевой
 * БД один и тот же поставщик заведён несколькими записями с разными ИНН
 * («Альбия» — три записи, цифры ИНН перемешаны распознаванием), поэтому
 * группировка по `supplier_directory_id` разложила бы его по трём группам.
 * Имена приходят с висячими и двойными пробелами, в разных регистрах,
 * с полной и сокращённой организационно-правовой формой — ключ приводит
 * всё это к одному виду.
 *
 * Обратная сторона: два разных юрлица с совпадающим названием, но разными
 * ИНН, попадут в одну группу. На боевых данных таких нет, а последствие
 * мягкое — документы окажутся соседними строками одной группы.
 */

/** Подпись хвостовой группы для строк без имени контрагента. */
internal const val UNKNOWN_SUPPLIER_LABEL = "Поставщик не указан"

/**
 * Служебные ключи групп. Namespace-префиксы разводят их и ключи реальных
 * контрагентов: настоящее имя всегда уходит под [REAL_PARTY_KEY_PREFIX],
 * поэтому совпасть со служебным ключом не может даже теоретически.
 */
internal const val UNKNOWN_PARTY_KEY = "system:unknown"
internal const val MANUAL_PARTY_KEY = "system:manual"
private const val REAL_PARTY_KEY_PREFIX = "party:"

/**
 * Группа строк одного контрагента.
 *
 * [key] — стабильный ключ для Compose (состояние раскрытия, `item(key = …)`),
 * [displayName] — то, что видит человек. Разделены намеренно: отображаемое имя
 * выбирается как самое частое написание в группе и потому меняется после
 * синхронизации — приехала ещё одна «Альбия» в другом регистре, и заголовок
 * мог бы переключиться. Будь имя ключом, у пользователя схлопнулась бы
 * раскрытая группа и дёрнулся LazyColumn.
 */
internal data class PartyGroup<T>(
    val key: String,
    val displayName: String,
    val rows: List<T>,
)

/**
 * Пробельные символы. `\s` в Java не ловит NBSP, поэтому явно добавлен
 * Unicode-класс разделителей плюс невидимые word-joiner и BOM.
 */
private val WHITESPACE = Regex("""[\s\p{Z}⁠﻿]+""")

/**
 * Кавычки всех сортов и точки. Меняются на пробел, а не удаляются: иначе
 * `ООО"АЛЬБИЯ"` и `ООО "АЛЬБИЯ"` дали бы разные ключи, а `И.И.` и `И. И.` —
 * тоже разные.
 */
private val QUOTES_AND_DOTS = Regex("""["«»„“”'‘’.]""")

/**
 * Организационно-правовые формы: полная запись → аббревиатура. Порядок важен —
 * длинные варианты идут раньше, иначе «публичное акционерное общество»
 * схлопнулось бы в «публичное ао».
 */
private val LEGAL_FORMS: List<Pair<String, String>> = listOf(
    "публичное акционерное общество" to "пао",
    "закрытое акционерное общество" to "зао",
    "открытое акционерное общество" to "оао",
    "акционерное общество" to "ао",
    "общество с ограниченной ответственностью" to "ооо",
    "индивидуальный предприниматель" to "ип",
    "торговый дом" to "тд",
)

/**
 * Нормализованный ключ группировки. Только для сравнения — на экран идёт
 * исходное написание (см. [groupByParty]).
 *
 * Дефисы намеренно сохраняются: они значимы в названиях
 * («Фаворит-Электро», «УралСибТрейд-МСК», «ЗИТАР-С»).
 */
internal fun partyGroupKey(name: String): String {
    val cleaned = name
        .lowercase()
        .replace('ё', 'е')
        .replace(QUOTES_AND_DOTS, " ")
        .replace(WHITESPACE, " ")
        .trim()
    if (cleaned.isEmpty()) return ""

    // Пробельные «поля» по краям, чтобы форма в начале и в конце строки
    // тоже сматчилась как отдельное слово, а не как часть соседнего.
    var padded = " ${glueSingleLetterRuns(cleaned)} "
    for ((full, abbr) in LEGAL_FORMS) padded = padded.replace(" $full ", " $abbr ")
    return padded.replace(WHITESPACE, " ").trim()
}

/**
 * Склеивает прогоны из двух и более односимвольных токенов: `о о о ромашка`
 * → `ооо ромашка`, `тд к с м` → `тд ксм`. Нужно после замены точек на пробелы,
 * иначе пунктирные формы вида `О.О.О.` рассыпались бы на буквы и не совпали
 * с обычным `ООО`.
 *
 * Прогоны длиной 1 не трогаем — иначе `м видео` превратилось бы в часть
 * соседнего слова, а такие имена склеивать не просили.
 */
private fun glueSingleLetterRuns(s: String): String {
    val out = mutableListOf<String>()
    val run = mutableListOf<String>()

    fun flushRun() {
        when {
            run.size >= 2 -> out.add(run.joinToString(""))
            run.size == 1 -> out.add(run.first())
        }
        run.clear()
    }

    for (token in s.split(' ')) {
        if (token.length == 1) {
            run.add(token)
        } else {
            flushRun()
            out.add(token)
        }
    }
    flushRun()
    return out.joinToString(" ")
}

/**
 * Группирует строки по нормализованному имени контрагента.
 *
 * Реальные группы идут по алфавиту без учёта регистра, группа [unknownLabel]
 * (строки с пустым или пробельным именем) — всегда последней.
 */
internal fun <T> groupByParty(
    rows: List<T>,
    unknownLabel: String = UNKNOWN_SUPPLIER_LABEL,
    nameOf: (T) -> String?,
): List<PartyGroup<T>> {
    val rowsByKey = LinkedHashMap<String, MutableList<T>>()
    val spellingsByKey = LinkedHashMap<String, MutableList<String>>()
    val unknown = mutableListOf<T>()

    for (row in rows) {
        val raw = nameOf(row)?.replace(WHITESPACE, " ")?.trim()
        val normalized = if (raw.isNullOrEmpty()) "" else partyGroupKey(raw)
        if (raw.isNullOrEmpty() || normalized.isEmpty()) {
            unknown.add(row)
            continue
        }
        val key = REAL_PARTY_KEY_PREFIX + normalized
        rowsByKey.getOrPut(key) { mutableListOf() }.add(row)
        spellingsByKey.getOrPut(key) { mutableListOf() }.add(raw)
    }

    val groups = rowsByKey
        .map { (key, items) ->
            PartyGroup(
                key = key,
                displayName = pickDisplayName(spellingsByKey.getValue(key)),
                rows = items.toList(),
            )
        }
        .sortedBy { it.displayName.lowercase() }

    return if (unknown.isEmpty()) {
        groups
    } else {
        groups + PartyGroup(UNKNOWN_PARTY_KEY, unknownLabel, unknown.toList())
    }
}

/**
 * Какое из написаний показать в заголовке группы. Выбор детерминированный,
 * иначе заголовок прыгал бы между перерисовками: самое частое написание,
 * при равенстве — самое короткое, при равенстве — лексикографически первое.
 */
private fun pickDisplayName(spellings: List<String>): String =
    spellings.groupingBy { it }.eachCount().entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.length }
                .thenBy { it.key },
        )
        .first().key
