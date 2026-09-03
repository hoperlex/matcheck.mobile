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

/** Слово в распознанном тексте. [height] — высота его рамки в пикселях кадра. */
data class OcrElement(val text: String, val height: Int)

/** Строка распознанного текста со своими словами. */
data class OcrLine(val text: String, val height: Int, val elements: List<OcrElement>)

/** Блок распознанного текста (у ML Kit — абзац). */
data class OcrBlock(val lines: List<OcrLine>)

/**
 * Кандидат в номера. [weight] — насколько крупно текст написан на кадре: номер на борту
 * обычно крупнее случайных надписей, и это единственный признак, по которому мы
 * различаем два ТС в кадре.
 */
data class PlateCandidate(val text: String, val weight: Int)

private const val LATIN_PLATE_LETTERS = "ABEKMHOPCTYX"
private const val CYRILLIC_PLATE_LETTERS = "АВЕКМНОРСТУХ"

private val CYRILLIC_TO_LATIN: Map<Char, Char> =
    CYRILLIC_PLATE_LETTERS.zip(LATIN_PLATE_LETTERS).toMap()

private val LATIN_TO_CYRILLIC: Map<Char, Char> =
    LATIN_PLATE_LETTERS.zip(CYRILLIC_PLATE_LETTERS).toMap()

/** Цифра, которую распознаватель мог принять за букву на буквенной позиции. */
private val DIGIT_AS_LETTER = mapOf('0' to 'O', '8' to 'B', '4' to 'A')

/** Буква, которую распознаватель мог принять за цифру на цифровой позиции. */
private val LETTER_AS_DIGIT = mapOf(
    'O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1',
    'S' to '5', 'B' to '8', 'Z' to '2',
)

/** Хвост «RUS» на номере — латиницей и в кириллическом написании после маппинга. */
private val COUNTRY_SUFFIXES = listOf("RUS", "PYC")

/**
 * Чистит распознанную строку: верхний регистр, только буквы и цифры, кириллица в
 * латиницу, срезанный хвост «RUS». Пробелы и дефисы выкидываем — на фото они читаются
 * непредсказуемо, а в шаблоне их всё равно нет.
 */
internal fun normalizeCandidate(raw: String): String {
    val compact = raw.uppercase().filter { it.isLetterOrDigit() }
    val latin = compact.map { CYRILLIC_TO_LATIN[it] ?: it }.joinToString("")
    return COUNTRY_SUFFIXES.firstOrNull { latin.endsWith(it) }
        ?.let { latin.dropLast(it.length) }
        ?: latin
}

/**
 * Позиционно чинит путаницу букв и цифр по шаблону «Б ЦЦЦ ББ ЦЦ(Ц)». Правка только
 * позиционная: «O» в позиции региона — почти наверняка ноль, но та же «O» в позиции
 * серии — настоящая буква, и трогать её нельзя.
 */
internal fun fixConfusions(s: String): String {
    if (s.length !in 8..9) return s
    return s.mapIndexed { i, c ->
        val isLetterPosition = i == 0 || i == 4 || i == 5
        if (isLetterPosition) DIGIT_AS_LETTER[c] ?: c else LETTER_AS_DIGIT[c] ?: c
    }.joinToString("")
}

/** Латиница обратно в кириллицу — канонический вид, в котором номер уходит в БД. */
internal fun toCyrillic(s: String): String =
    s.map { LATIN_TO_CYRILLIC[it] ?: it }.joinToString("")

/**
 * Строка → номер по ГОСТ в кириллице, либо null. Это единственный фильтр: всё, что не
 * легло в [RU_RE], отбрасывается молча. Спецтехника и прицепы (около 2 % записей в БД)
 * под шаблон не попадают и вводятся руками, как и раньше.
 */
internal fun canonicalisePlate(raw: String): String? {
    val normalized = normalizeCandidate(raw)
    if (normalized.length !in 8..9) return null
    val fixed = fixConfusions(normalized)
    return if (RU_RE.matches(fixed)) toCyrillic(fixed) else null
}

/**
 * Собирает кандидатов из распознанного текста. Номер приезжает по-разному, поэтому
 * пробуем все разумные склейки:
 *  - отдельное слово — обычный случай, номер целиком;
 *  - соседние слова внутри строки — номер и регион часто разъезжаются на два слова;
 *  - строка целиком;
 *  - соседние строки блока — двухстрочная табличка «A123BC» / «77 RUS»;
 *  - блок целиком.
 *
 * Дубликаты не страшны: [pickPlate] группирует кандидатов по распознанному номеру.
 */
internal fun buildCandidates(blocks: List<OcrBlock>): List<PlateCandidate> {
    val out = mutableListOf<PlateCandidate>()
    for (block in blocks) {
        for (line in block.lines) {
            out += PlateCandidate(line.text, line.height)
            line.elements.forEach { out += PlateCandidate(it.text, it.height) }
            // Окна соседних слов: вес окна — по самому мелкому слову в нём, чтобы
            // склейка не получила вес крупной надписи из-за одного большого слова.
            for (size in 2..3) {
                line.elements.windowed(size) { window ->
                    out += PlateCandidate(
                        window.joinToString("") { it.text },
                        window.minOf { it.height },
                    )
                }
            }
        }
        block.lines.windowed(2) { window ->
            out += PlateCandidate(
                window.joinToString("") { it.text },
                window.minOf { it.height },
            )
        }
        if (block.lines.size > 1) {
            out += PlateCandidate(
                block.lines.joinToString("") { it.text },
                // Средняя высота строк, а не высота блока: многострочный блок иначе
                // получил бы завышенный вес и выиграл бы у настоящего номера.
                block.lines.sumOf { it.height } / block.lines.size,
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
 * Порог сравнивает только РАЗНЫЕ номера: это защита от кадра, где видно два ТС. Лучше
 * не подставить ничего, чем подставить номер соседней машины.
 */
internal fun pickPlate(candidates: List<PlateCandidate>): String? {
    val byPlate = mutableMapOf<String, Int>()
    for (candidate in candidates) {
        val plate = canonicalisePlate(candidate.text) ?: continue
        byPlate[plate] = maxOf(byPlate[plate] ?: Int.MIN_VALUE, candidate.weight)
    }
    val ranked = byPlate.entries.sortedByDescending { it.value }
    return when {
        ranked.isEmpty() -> null
        ranked.size == 1 -> ranked[0].key
        // Вес 0 (рамки не было) не может выиграть сравнение: 0 >= 0 * 1.3 верно
        // арифметически, но означает «мы ничего не знаем о размере».
        ranked[0].value > 0 && ranked[0].value >= ranked[1].value * AMBIGUITY_RATIO -> ranked[0].key
        else -> null
    }
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
