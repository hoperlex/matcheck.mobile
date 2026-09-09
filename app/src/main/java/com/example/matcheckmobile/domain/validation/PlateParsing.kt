package com.example.matcheckmobile.domain.validation

/**
 * Разбор госномера в тексте, распознанном с фото машины.
 *
 * Здесь нет ни одного Android-типа и ни одного типа ML Kit — намеренно. Вся логика,
 * в которой можно ошибиться (склейка кандидатов, дедупликация, арбитраж с ручным вводом),
 * живёт в чистых функциях и покрывается обычным JVM-тестом; обёртка над распознавателем
 * (media/PlateRecognizer.kt) только перекладывает результат ML Kit в модель ниже.
 *
 * Буквы ГОСТ Р 50577 — это ровно те 12, что визуально совпадают с латиницей, поэтому
 * латинский распознаватель их читает; мы приводим кандидата к латинице, проверяем
 * шаблоном [RU_RE] из VehiclePlate.kt и возвращаем результат в кириллице — так пишет
 * инспектор руками, и так лежит в БД (9 525 из 9 551 записей).
 */

/**
 * Рамка в координатах кадра. Своя, а не из Android или ML Kit: вся логика ниже
 * обязана оставаться чистой, чтобы покрываться обычным JVM-тестом.
 */
data class OcrRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun union(other: OcrRect): OcrRect = OcrRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

/** Слово в распознанном тексте. */
data class OcrElement(val text: String, val bounds: OcrRect)

/** Строка распознанного текста со своими словами. */
data class OcrLine(val text: String, val bounds: OcrRect, val elements: List<OcrElement>)

/** Блок распознанного текста (у ML Kit — абзац). */
data class OcrBlock(val lines: List<OcrLine>)

/**
 * Кандидат в номера. [weight] — насколько крупно текст написан на кадре: номер на борту
 * обычно крупнее случайных надписей, и это единственный признак, по которому мы
 * различаем два ТС в кадре. Для многострочного кандидата вес считается по средней
 * высоте строк, а не по высоте всей рамки, иначе блок текста побеждал бы номер.
 */
data class PlateCandidate(val text: String, val bounds: OcrRect, val weight: Int)

/**
 * Победивший кандидат вместе с рамкой — по ней делается второй проход.
 *
 * Уровень доверия здесь НЕ хранится: он определяется только после сравнения обоих
 * проходов, см. [decideReading].
 */
data class SelectedPlate(
    val match: FormatMatch,
    val bounds: OcrRect,
    val weight: Int,
    /** Кандидат собран склейкой, потребовавшей расширенного порога (готовность к релизу B). */
    val wideGlue: Boolean = false,
) {
    val canonical: String get() = match.canonical
}

private const val LATIN_PLATE_LETTERS = "ABEKMHOPCTYX"
private const val CYRILLIC_PLATE_LETTERS = "АВЕКМНОРСТУХ"

private val CYRILLIC_TO_LATIN: Map<Char, Char> =
    CYRILLIC_PLATE_LETTERS.zip(LATIN_PLATE_LETTERS).toMap()

private val LATIN_TO_CYRILLIC: Map<Char, Char> =
    LATIN_PLATE_LETTERS.zip(CYRILLIC_PLATE_LETTERS).toMap()

/**
 * Цифра, которую распознаватель мог принять за букву на буквенной позиции.
 *
 * Карта намеренно НЕ симметрична [LETTER_AS_DIGIT]: `4 → A` здесь есть, а обратного
 * `A → 4` там нет. Это осознанно — буква «A» на цифровой позиции почти всегда настоящая
 * буква, тогда как четвёрка на буквенной позиции почти всегда ошибка чтения. Добавлять
 * обратные правки можно только там, где такое обоснование есть.
 */
private val DIGIT_AS_LETTER = mapOf('0' to 'O', '8' to 'B', '4' to 'A')

/** Буква, которую распознаватель мог принять за цифру на цифровой позиции. */
private val LETTER_AS_DIGIT = mapOf(
    'O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1',
    'S' to '5', 'B' to '8',
    // 'Z' to '2' убрана: это был единственный путь, которым разбор мог СИНТЕЗИРОВАТЬ
    // двойку из не-цифры, а в бой уехали четыре номера, где последняя цифра региона
    // стала именно двойкой (797→792, 799→792, 977→972). Причастность именно этой
    // пары не доказана — ML Kit мог прочитать «2» и сам, — но угадывать конкретную
    // цифру за «Z» оснований нет: это ошибка чтения, а не известная путаница.
)

/**
 * Хвост «RUS» на номере — в обеих раскладках. Хвостовой код безобиден: он стоит справа от
 * номера и его снятие ничего не портит.
 */
private val COUNTRY_SUFFIXES = listOf("RUS", "РУС", "PYC")

/**
 * Коды страны, которые печатают СЛЕВА от номера (белорусская синяя полоса и аналоги).
 *
 * Снимать их вслепую всё равно не станем: по одной строке код страны от начала номера
 * неотличим, и в каталоге в любой момент может появиться формат, который начинается с
 * букв. Поэтому строка разбирается ДВАЖДЫ — целиком и без префикса, — а выбор делает
 * обычный арбитраж по числу исправлений.
 *
 * «UA» намеренно нет: украинских форматов в каталоге тоже нет, и снятие префикса лишь
 * породило бы обрывок, способный лечь на чужую маску.
 */
private val COUNTRY_PREFIXES: Map<String, String> = mapOf(
    "BY" to "BY",
    "KZ" to "KZ",
    "RUS" to "RU",
    "RU" to "RU",
)

/**
 * Трёхзначные коды региона, которым доверяем автозаполнение.
 *
 * **Это НЕ официальный перечень.** Официальный — приложение №1 к приказу МВД №766; когда он
 * будет под рукой, константу следует заменить или расширить им.
 *
 * Выведен 08.09.2026 из проверяемого источника: коды, которые инспекторы этого парка
 * набирали РУКАМИ до появления распознавания, то есть заведомо без участия OCR. Правило:
 * формат ГОСТ с трёхзначным регионом, `created_at` до выкатки 1.0.38 (07.09 12:00),
 * частота ≥3, исключая коды с ведущим нулём — трёхзначного кода с нулём впереди не бывает,
 * это опечатка двузначного (`077` = `77`, 8 записей).
 *
 * Зачем вообще: 1.0.38 записал в прод четыре номера, где последняя цифра ТРЁХЗНАЧНОГО
 * региона была испорчена (`797→792`, `799→792`, `977→972`), тогда как легитимные редкие
 * номера из той же выборки — двузначные (`15`, `08`, `79`). Поэтому фильтр применяется
 * только к трёхзначным и не понижает ни одного наблюдавшегося настоящего номера.
 *
 * Ошибаться в сторону неполноты безопасно: пропущенный настоящий код означает подсказку
 * вместо автоподстановки, то есть один тап инспектора.
 *
 * `323` оставлен под вопросом: 11 записей, но схеме кодов не соответствует. Все 11 —
 * ручной ввод, поэтому вероятнее реальная машина, чем повторяющаяся опечатка. Если
 * официальный перечень его не подтвердит — убрать.
 */
private val RU_THREE_DIGIT_AUTO_REGIONS = setOf(
    "105", "124", "126", "133", "134", "136", "138", "147", "150", "152",
    "156", "161", "164", "172", "177", "181", "190", "193", "196", "197",
    "198", "199", "250", "252", "323", "550", "702", "716", "750", "761",
    "763", "774", "777", "790", "797", "799", "977", "997",
)

/**
 * Описание одного формата номера.
 *
 * Форматы — данные, а не код: новый добавляется строчкой в [PLATE_FORMATS], без правки
 * логики разбора. Это прямое следствие того, что белорусские номера оказались невидимы
 * почти два месяца — шаблон был ровно один и зашит в regex.
 *
 * @param masks допустимые формы: `L` — буква, `D` — цифра.
 * @param letters алфавит формата. Для нероссийских здесь ОБЕ раскладки: инспектор
 *   набирает на русской клавиатуре, а распознаватель читает латиницей.
 * @param transliterate приводить ли кириллицу к латинице при разборе и обратно на выходе.
 *   Осмысленно только для ГОСТ, где 12 букв однозначно сопоставимы. Казахстанский
 *   `067АЛК04` содержит «Л», которой в таблице омоглифов нет вовсе.
 * @param separatorAfter позиция, после которой в каноническом виде ставится дефис.
 * @param extraFixes правки, специфичные для формата, сверх общих карт.
 * @param autoFillable можно ли подставлять без подтверждения инспектора.
 */
private data class PlateFormat(
    val id: String,
    /** Страна формата — по ней ограничивается разбор, когда на кадре виден код страны. */
    val country: String,
    val masks: List<String>,
    val letters: Set<Char>,
    val transliterate: Boolean,
    val separatorAfter: Int? = null,
    val extraFixes: Map<Char, Char> = emptyMap(),
    val autoFillable: Boolean,
)

private val RU_LETTERS: Set<Char> = LATIN_PLATE_LETTERS.toSet()

/** Белорусский алфавит номеров — те же омоглифы плюс «I», в обеих раскладках. */
private val BY_LETTERS: Set<Char> =
    (LATIN_PLATE_LETTERS + CYRILLIC_PLATE_LETTERS + "I" + "І").toSet()

/** Казахстан: буквы шире российского набора, поэтому обе раскладки целиком. */
private val KZ_LETTERS: Set<Char> =
    (('A'..'Z') + ('А'..'Я')).toSet()

/**
 * Каталог наблюдаемых в нашей эксплуатации форматов.
 *
 * ВАЖНО: собран по тому, что реально встречалось в базе, а не по действующим редакциям
 * стандартов — официальные правила РФ и Казахстана шире. Порядок в списке НЕ является
 * приоритетом: арбитраж в [canonicalisePlate] сравнивает число исправлений.
 */
private val PLATE_FORMATS = listOf(
    // ГОСТ Р 50577, легковой/грузовой — 98 % потока.
    PlateFormat(
        id = "ru_car",
        country = "RU",
        masks = listOf("LDDDLLDD", "LDDDLLDDD"),
        letters = RU_LETTERS,
        transliterate = true,
        autoFillable = true,
    ),
    // Прицепы, мото, спецтехника: четыре цифры впереди.
    PlateFormat(
        id = "ru_digits_first",
        country = "RU",
        masks = listOf("DDDDLLDD", "DDDDLLDDD"),
        letters = RU_LETTERS,
        transliterate = true,
        autoFillable = true,
    ),
    // Прицепов с буквами впереди («LLDDDDDD») в каталоге СОЗНАТЕЛЬНО НЕТ.
    //
    // Маска «две буквы, дальше сплошные цифры» превращает в номер любой ряд из восьми
    // цифр: достаточно, чтобы первые две прошли через DIGIT_AS_LETTER. На реальном кадре
    // 08.09.2026 дала «ОВ092026», и, поскольку штамп был набран крупнее знака, она
    // выиграла у настоящего «А647ОУ797» ещё в pickPlate — верный номер до арбитража не
    // дошёл. Она же глотала белорусский BY2971OI-4 целиком, отдавая «ВУ2971014».
    //
    // Цена отказа: таких номеров два из 10 156 записей за всю историю, оба введены
    // руками, и автозаполнения этот формат всё равно не получал. Инспектор продолжит
    // вводить их руками — ровно как раньше.
    // Беларусь, легковой: 4633КА-6. Только подсказкой — маска начинается с цифр и
    // потому легче собирается из посторонних надписей на борту.
    PlateFormat(
        id = "by_1",
        country = "BY",
        masks = listOf("DDDDLLD"),
        letters = BY_LETTERS,
        transliterate = false,
        separatorAfter = 5,
        extraFixes = mapOf('1' to 'I'),
        autoFillable = false,
    ),
    // Беларусь, вторая наблюдаемая форма: АМ5351-5.
    PlateFormat(
        id = "by_2",
        country = "BY",
        masks = listOf("LLDDDDD"),
        letters = BY_LETTERS,
        transliterate = false,
        separatorAfter = 5,
        extraFixes = mapOf('1' to 'I'),
        autoFillable = false,
    ),
    // Казахстан: 067АЛК04.
    PlateFormat(
        id = "kz",
        country = "KZ",
        masks = listOf("DDDLLLDD"),
        letters = KZ_LETTERS,
        transliterate = false,
        autoFillable = false,
    ),
)

/**
 * Можно ли доверить автозаполнение региону этого номера.
 *
 * Проверяется ТОЛЬКО у российских форматов и ТОЛЬКО по канонизированному значению: у
 * белорусского и казахстанского регион устроен иначе, а они автозаполнения не получают
 * в принципе. К ручному вводу отношения не имеет — фильтр живёт в [decideReading].
 *
 * Двузначный код принимается как есть в диапазоне 01–99: все четыре реальные ошибки были
 * в трёхзначных, а легитимные редкие номера парка (`15`, `08`, `79`) — двузначные, и
 * понижать их не за что. `00` регионом не является.
 */
internal fun regionAllowsAutoFill(canonical: String, formatId: String): Boolean {
    if (!formatId.startsWith("ru_")) return true
    val digits = canonical.takeLastWhile { it.isDigit() }
    return when (digits.length) {
        2 -> digits != "00"
        3 -> digits in RU_THREE_DIGIT_AUTO_REGIONS
        else -> false
    }
}

/** Уровень доверия к прочтению. */
enum class PlateTier { AUTO, SUGGESTION }

/**
 * Совпадение строки с одним форматом каталога.
 *
 * @param corrections сколько символов пришлось починить. Ноль — точное совпадение.
 * @param ambiguous строка одинаково хорошо легла на несколько форматов; автозаполнять нельзя.
 */
data class FormatMatch(
    val canonical: String,
    val formatId: String,
    val corrections: Int,
    val ambiguous: Boolean = false,
    /**
     * Разбор потребовал снять код страны В НАЧАЛЕ строки. Значит номер прочитан не
     * полностью, и автозаполнять его нельзя ни при каком совпадении проходов.
     *
     * Хранится отдельно от wideGlue намеренно: в телеметрии это разные причины, и
     * смешивать работу с белорусским префиксом с «широкой склейкой» нельзя — по журналу
     * потом не разобраться.
     */
    val countryAffixStripped: Boolean = false,
) {
    val autoFillable: Boolean
        get() = !ambiguous && !countryAffixStripped &&
            PLATE_FORMATS.first { it.id == formatId }.autoFillable
}

/**
 * Чистит распознанную строку: верхний регистр, только буквы и цифры, срезанный хвост «RUS».
 *
 * Транслитерации здесь НЕТ — она стала свойством формата. Раньше кириллица приводилась к
 * латинице глобально, и казахстанский номер с «Л» превращался в кашу из двух раскладок.
 */
internal fun normalizeCandidate(raw: String): String {
    val compact = raw.uppercase().filter { it.isLetterOrDigit() }
    return COUNTRY_SUFFIXES.firstOrNull { compact.endsWith(it) }
        ?.let { compact.dropLast(it.length) }
        ?: compact
}

/**
 * Вариант разбора строки: тело и страна, чей начальный код с него сняли.
 *
 * @param country null — префикс не снимали, тело разбирается всеми форматами.
 */
internal data class NormalizedCandidate(val body: String, val country: String? = null)

/**
 * Строит варианты разбора одной строки.
 *
 * Хвостовой код страны («RUS» справа от номера) снимается всегда и безоговорочно — он
 * стоит отдельно от знака и ничего не значит для формата.
 *
 * Начальный код добавляет ВТОРОЙ вариант, а не заменяет первый: отличить код страны от
 * начала номера по одной строке невозможно, и слепое снятие префикса испортило бы номер,
 * который просто начинается с этих букв. Проверяются оба чтения, а победителя выбирает
 * обычный арбитраж по числу исправлений: для «BY2971OI4» тело «2971OI4» ложится на by_1
 * с нулём правок, тогда как целиком строка не ложится ни на один формат.
 */
internal fun candidateVariants(raw: String): List<NormalizedCandidate> {
    val body = normalizeCandidate(raw)
    if (body.isEmpty()) return emptyList()

    val variants = mutableListOf(NormalizedCandidate(body))
    COUNTRY_PREFIXES.entries
        .filter { body.startsWith(it.key) && body.length > it.key.length }
        // Самый длинный код вперёд, иначе «RUS» разобрался бы как «RU» + мусорная «S».
        .maxByOrNull { it.key.length }
        ?.let { (code, country) -> variants += NormalizedCandidate(body.drop(code.length), country) }
    return variants
}

/** Латиница обратно в кириллицу — канонический вид, в котором номер уходит в БД. */
internal fun toCyrillic(s: String): String =
    s.map { LATIN_TO_CYRILLIC[it] ?: it }.joinToString("")

/**
 * Пробует уложить строку на конкретный формат, считая число исправлений.
 *
 * Правка строго позиционная: «O» в позиции региона — почти наверняка ноль, но та же «O»
 * в позиции серии — настоящая буква, и трогать её нельзя.
 */
private fun matchFormat(normalized: String, format: PlateFormat): FormatMatch? {
    for (mask in format.masks) {
        if (mask.length != normalized.length) continue
        var corrections = 0
        val out = StringBuilder(normalized.length)
        var ok = true
        for (i in normalized.indices) {
            val raw = normalized[i]
            val c = if (format.transliterate) CYRILLIC_TO_LATIN[raw] ?: raw else raw
            if (mask[i] == 'L') {
                val fixed = when {
                    c in format.letters -> c
                    DIGIT_AS_LETTER[c]?.takeIf { it in format.letters } != null -> {
                        corrections++; DIGIT_AS_LETTER.getValue(c)
                    }
                    format.extraFixes[c]?.takeIf { it in format.letters } != null -> {
                        corrections++; format.extraFixes.getValue(c)
                    }
                    else -> { ok = false; c }
                }
                out.append(fixed)
            } else {
                val fixed = when {
                    c.isDigit() -> c
                    LETTER_AS_DIGIT[c] != null -> { corrections++; LETTER_AS_DIGIT.getValue(c) }
                    else -> { ok = false; c }
                }
                out.append(fixed)
            }
            if (!ok) break
        }
        if (!ok) continue
        val body = if (format.transliterate) toCyrillic(out.toString()) else out.toString()
        val canonical = format.separatorAfter
            ?.takeIf { it in 0 until body.lastIndex }
            ?.let { body.substring(0, it + 1) + "-" + body.substring(it + 1) }
            ?: body
        return FormatMatch(canonical, format.id, corrections)
    }
    return null
}

/**
 * Строка → номер в каноническом виде, либо null.
 *
 * **Арбитраж детерминирован и не зависит от порядка каталога.** Одна строка может лечь на
 * несколько форматов: `0123BC77` — это и ГОСТ (после починки `0 → O`), и формат с цифрами
 * впереди (вообще без починки). Побеждает совпадение с наименьшим числом исправлений;
 * точное совпадение бьёт любое исправленное. Ничья означает, что выбрать безопасно нельзя:
 * возвращаем помеченный `ambiguous` результат, который автозаполнению не подлежит.
 */
internal fun canonicalisePlate(raw: String): FormatMatch? {
    val matches = candidateVariants(raw).flatMap { variant ->
        // Снятый код страны сужает выбор до форматов этой страны: обрывок белорусского
        // номера не должен даже пробоваться на российские маски.
        val formats = if (variant.country == null) {
            PLATE_FORMATS
        } else {
            PLATE_FORMATS.filter { it.country == variant.country }
        }
        formats.mapNotNull { format ->
            matchFormat(variant.body, format)
                ?.copy(countryAffixStripped = variant.country != null)
        }
    }
    return arbitrate(matches)
}

/**
 * Выбирает победителя среди совпадений с разными форматами.
 *
 * Вынесено отдельно ради теста: на текущем каталоге ничья структурно недостижима (маски
 * `ru_car` и `ru_digits_first` различаются ровно одной позицией, поэтому их числа
 * исправлений всегда отличаются на единицу), но правило обязано быть покрыто до того,
 * как в каталог добавят формат, который ничью сделает возможной.
 */
internal fun arbitrate(matches: List<FormatMatch>): FormatMatch? {
    if (matches.isEmpty()) return null
    // Сортировка по formatId вторым ключом — чтобы при ничьей результат был
    // воспроизводим, но НЕ зависел от порядка объявления в каталоге.
    val ranked = matches.sortedWith(compareBy({ it.corrections }, { it.formatId }))
    val best = ranked.first()
    val tie = ranked.size > 1 && ranked[1].corrections == best.corrections
    return best.copy(ambiguous = tie)
}

/**
 * Зазор между рамками по горизонтали. Отрицательный — рамки перекрываются.
 */
private fun horizontalGap(a: OcrRect, b: OcrRect): Int =
    maxOf(a.left, b.left) - minOf(a.right, b.right)

/** Зазор между рамками по вертикали. Отрицательный — рамки перекрываются. */
private fun verticalGap(a: OcrRect, b: OcrRect): Int =
    maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)

/** Доля перекрытия по горизонтали относительно более узкой рамки. */
private fun horizontalOverlapRatio(a: OcrRect, b: OcrRect): Double {
    val narrower = minOf(a.width, b.width)
    if (narrower <= 0) return 0.0
    val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
    return overlap.toDouble() / narrower
}

/**
 * Два фрагмента стоят рядом в одной строке — их можно склеивать.
 *
 * Без этой проверки к «М583МУ79» приваривалась далёкая двойка с борта машины, и
 * получался несуществующий регион. Порог — примерно ширина символа: номер и код
 * региона разделены узким зазором, а посторонняя надпись стоит заметно дальше.
 */
private fun sameLineAdjacent(a: OcrRect, b: OcrRect): Boolean {
    val charHeight = maxOf(a.height, b.height)
    if (charHeight <= 0) return false
    return horizontalGap(a, b) <= charHeight && verticalGap(a, b) <= charHeight / 2
}

/**
 * Две строки стоят одна под другой — это может быть двухстрочная табличка
 * «A123BC» / «77 RUS». Требуем и малый вертикальный зазор, и существенное
 * перекрытие по горизонтали: иначе склеятся строки из разных концов кадра.
 */
private fun stackedAdjacent(a: OcrRect, b: OcrRect): Boolean {
    val lineHeight = maxOf(a.height, b.height)
    if (lineHeight <= 0) return false
    return verticalGap(a, b) <= lineHeight && horizontalOverlapRatio(a, b) >= MIN_STACK_OVERLAP
}

private const val MIN_STACK_OVERLAP = 0.3

/**
 * Собирает кандидатов из распознанного текста.
 *
 * Номер приезжает по-разному, поэтому пробуем все разумные склейки. Но **любая**
 * составная склейка требует геометрической связности: проверять зазор только у
 * окон слов бесполезно, потому что строка целиком, соседние строки и блок целиком
 * собрали бы тот же ложный номер другим путём.
 *
 * Дубликаты не страшны: [pickPlate] группирует кандидатов по распознанному номеру.
 */
internal fun buildCandidates(blocks: List<OcrBlock>): List<PlateCandidate> {
    val out = mutableListOf<PlateCandidate>()
    for (block in blocks) {
        for (line in block.lines) {
            line.elements.forEach { out += PlateCandidate(it.text, it.bounds, it.bounds.height) }

            // Окна соседних слов: вес окна — по самому мелкому слову в нём, чтобы
            // склейка не получила вес крупной надписи из-за одного большого слова.
            for (size in 2..3) {
                line.elements.windowed(size).forEach { window ->
                    if (window.zipWithNext().all { (a, b) -> sameLineAdjacent(a.bounds, b.bounds) }) {
                        out += PlateCandidate(
                            text = window.joinToString("") { it.text },
                            bounds = window.map { it.bounds }.reduce(OcrRect::union),
                            weight = window.minOf { it.bounds.height },
                        )
                    }
                }
            }

            // Строка целиком — только если её собственные слова связны.
            val lineIsCoherent = line.elements.size <= 1 ||
                line.elements.zipWithNext().all { (a, b) -> sameLineAdjacent(a.bounds, b.bounds) }
            if (lineIsCoherent) {
                out += PlateCandidate(line.text, line.bounds, line.bounds.height)
            }
        }

        // Соседние строки блока — двухстрочный номер.
        block.lines.windowed(2).forEach { window ->
            if (stackedAdjacent(window[0].bounds, window[1].bounds)) {
                out += PlateCandidate(
                    text = window.joinToString("") { it.text },
                    bounds = window[0].bounds.union(window[1].bounds),
                    weight = window.sumOf { it.bounds.height } / window.size,
                )
            }
        }

        // Блок целиком — только когда все строки связны по вертикали.
        if (block.lines.size > 1 &&
            block.lines.zipWithNext().all { (a, b) -> stackedAdjacent(a.bounds, b.bounds) }
        ) {
            out += PlateCandidate(
                text = block.lines.joinToString("") { it.text },
                bounds = block.lines.map { it.bounds }.reduce(OcrRect::union),
                // Средняя высота строк, а не высота блока: иначе многострочный
                // блок получил бы завышенный вес и выиграл бы у настоящего номера.
                weight = block.lines.sumOf { it.bounds.height } / block.lines.size,
            )
        }
    }
    return out
}

/** Во сколько раз лучший кандидат должен быть крупнее следующего, чтобы ему верить. */
private const val AMBIGUITY_RATIO = 1.3

/**
 * Выбирает номер из кандидатов либо возвращает null, если уверенности нет.
 *
 * Дедупликация обязательна и идёт ДО проверки неоднозначности: один и тот же номер
 * приходит несколькими путями (слово, склейка слов, строка, блок), и без группировки
 * порог [AMBIGUITY_RATIO] отвергал бы правильный результат почти всегда — сравнивались
 * бы два вхождения одного номера.
 *
 * Порог сравнивает только РАЗНЫЕ номера: это защита от кадра, где видно два ТС.
 */
internal fun pickPlate(candidates: List<PlateCandidate>): SelectedPlate? {
    data class Scored(val match: FormatMatch, val candidate: PlateCandidate)

    val bestByPlate = mutableMapOf<String, Scored>()
    for (candidate in candidates) {
        val match = canonicalisePlate(candidate.text) ?: continue
        val known = bestByPlate[match.canonical]
        if (known == null || candidate.weight > known.candidate.weight) {
            bestByPlate[match.canonical] = Scored(match, candidate)
        }
    }
    val ranked = bestByPlate.values.sortedByDescending { it.candidate.weight }
    fun Scored.selected() = SelectedPlate(match, candidate.bounds, candidate.weight)
    return when {
        ranked.isEmpty() -> null
        ranked.size == 1 -> ranked[0].selected()
        // Вес 0 (рамки не было) не может выиграть спор: арифметически 0 >= 0 * 1.3
        // верно, но означает «мы ничего не знаем о размере».
        ranked[0].candidate.weight > 0 &&
            ranked[0].candidate.weight >= ranked[1].candidate.weight * AMBIGUITY_RATIO ->
            ranked[0].selected()
        else -> null
    }
}

/** Почему прочтение получило свой уровень — уходит в телеметрию, не в UI. */
enum class PlateReasonCode {
    AGREED, DISAGREED, SECOND_EMPTY, FORMAT_NOT_AUTO, AMBIGUOUS_FORMAT, WIDE_GLUE,

    /**
     * Номер собран только после снятия начального кода страны — значит прочитан не
     * полностью. Отдельно от WIDE_GLUE намеренно: иначе в журнале работа с белорусским
     * префиксом выглядела бы как широкая склейка.
     */
    COUNTRY_AFFIX,

    /**
     * Трёхзначный код региона не входит в список тех, которым доверяем автозаполнение.
     * Отдельно от FORMAT_NOT_AUTO: иначе в телеметрии несуществующий регион сольётся с
     * «формат не автозаполняемый», и класс ошибки из 1.0.38 останется невидимым.
     */
    RU_REGION_NOT_AUTO,
}

/** Итог распознавания одного кадра. */
data class PlateReading(
    val text: String,
    val formatId: String,
    val tier: PlateTier,
    val reason: PlateReasonCode,
)

/**
 * Решает, что делать с результатами двух проходов.
 *
 * Вынесено отдельной чистой функцией именно ради теста: таблица решений — то место, где
 * ошибка стоит неверного номера в базе, а проверить её на устройстве почти невозможно.
 *
 * **Автозаполнения сейчас нет ни при каком исходе — любое прочтение идёт подсказкой.**
 * Причина: инспекторы с двух объектов сообщили, что в поле попадает не тот регион
 * («вместо 977 может вбить 02»), а такую ошибку не видно ни в базе, ни здесь. Коды
 * `02` и `799` существуют, поэтому [regionAllowsAutoFill] их пропускает, а согласие двух
 * проходов доказательством не является: второй проход режет кадр ПО РАМКЕ ПЕРВОГО
 * ([cropRect], запас 20 %) и цифру, которую первый не увидел, увидеть не может — он
 * подтверждает ошибку, а не исправляет.
 *
 * Уровни и коды причин оставлены целиком: [PlateTier.AUTO] вернётся одной строкой, когда
 * дамп `PlateRecognizer` с реального кадра покажет причину. До тех пор журнал продолжает
 * различать классы, чтобы было с чем сравнивать.
 *
 * При расхождении предлагаем результат ВТОРОГО прохода: он читает вырезанную рамку в
 * полном разрешении, и именно мелкие цифры региона были причиной ошибки `М583МУ792`.
 */
fun decideReading(first: SelectedPlate?, second: SelectedPlate?): PlateReading? {
    val chosen = second ?: first ?: return null
    val match = chosen.match

    fun suggestion(reason: PlateReasonCode) =
        PlateReading(match.canonical, match.formatId, PlateTier.SUGGESTION, reason)

    return when {
        // Ложная склейка может совпасть в обоих проходах — модель и парсер одни и те же,
        // поэтому согласие проходов её не отсеивает. Такой кандидат только подсказкой.
        chosen.wideGlue || first?.wideGlue == true -> suggestion(PlateReasonCode.WIDE_GLUE)
        match.countryAffixStripped -> suggestion(PlateReasonCode.COUNTRY_AFFIX)
        match.ambiguous -> suggestion(PlateReasonCode.AMBIGUOUS_FORMAT)
        // Раньше общей проверки формата: иначе несуществующий регион в телеметрии
        // выглядел бы как «формат не автозаполняемый».
        !regionAllowsAutoFill(match.canonical, match.formatId) ->
            suggestion(PlateReasonCode.RU_REGION_NOT_AUTO)
        !match.autoFillable -> suggestion(PlateReasonCode.FORMAT_NOT_AUTO)
        second == null -> suggestion(PlateReasonCode.SECOND_EMPTY)
        first == null -> suggestion(PlateReasonCode.SECOND_EMPTY)
        first.canonical != second.canonical -> suggestion(PlateReasonCode.DISAGREED)
        // Здесь стояло PlateTier.AUTO. Причина остаётся AGREED: по журналу должно быть
        // видно, сколько прочтений дошло бы до автозаполнения, если его вернуть.
        else -> suggestion(PlateReasonCode.AGREED)
    }
}

/**
 * Рамка кандидата, пересчитанная в координаты оригинального кадра, расширенная
 * и обрезанная по его границам.
 *
 * Раздельные scaleX и scaleY нужны потому, что после inSampleSize и
 * scaleToMaxSide пропорции могут не совпасть до пикселя. Clamp обязателен:
 * BitmapRegionDecoder бросает на выходе за границы изображения.
 */
internal fun cropRect(
    bounds: OcrRect,
    decodedWidth: Int,
    decodedHeight: Int,
    originalWidth: Int,
    originalHeight: Int,
    expandRatio: Double = 0.2,
): OcrRect? {
    if (decodedWidth <= 0 || decodedHeight <= 0) return null
    if (originalWidth <= 0 || originalHeight <= 0) return null

    val scaleX = originalWidth.toDouble() / decodedWidth
    val scaleY = originalHeight.toDouble() / decodedHeight
    val padX = bounds.width * scaleX * expandRatio
    val padY = bounds.height * scaleY * expandRatio

    val left = ((bounds.left * scaleX) - padX).toInt().coerceIn(0, originalWidth - 1)
    val top = ((bounds.top * scaleY) - padY).toInt().coerceIn(0, originalHeight - 1)
    val right = ((bounds.right * scaleX) + padX).toInt().coerceIn(left + 1, originalWidth)
    val bottom = ((bounds.bottom * scaleY) + padY).toInt().coerceIn(top + 1, originalHeight)

    val rect = OcrRect(left, top, right, bottom)
    return if (rect.width > 0 && rect.height > 0) rect else null
}

/**
 * Правило арбитража: можно ли подставить распознанный номер в поле.
 *
 * @return новое значение поля, либо null — если текущее менять нельзя.
 *
 * Проверки `editedByUser` недостаточно заменить на `current.isBlank()`: инспектор мог
 * стереть номер намеренно, и следующее фото вписало бы его заново.
 */
fun plateAfterOcr(current: String, editedByUser: Boolean, recognised: String): String? =
    if (editedByUser || current.isNotBlank()) null else recognised
