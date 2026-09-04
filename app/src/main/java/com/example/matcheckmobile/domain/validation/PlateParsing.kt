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

/** Победивший кандидат вместе с рамкой — по ней делается второй проход. */
data class SelectedPlate(val canonical: String, val bounds: OcrRect, val weight: Int)

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
    val bestByPlate = mutableMapOf<String, PlateCandidate>()
    for (candidate in candidates) {
        val plate = canonicalisePlate(candidate.text) ?: continue
        val known = bestByPlate[plate]
        if (known == null || candidate.weight > known.weight) bestByPlate[plate] = candidate
    }
    val ranked = bestByPlate.entries.sortedByDescending { it.value.weight }
    fun Map.Entry<String, PlateCandidate>.selected() =
        SelectedPlate(key, value.bounds, value.weight)
    return when {
        ranked.isEmpty() -> null
        ranked.size == 1 -> ranked[0].selected()
        // Вес 0 (рамки не было) не может выиграть спор: арифметически 0 >= 0 * 1.3
        // верно, но означает «мы ничего не знаем о размере».
        ranked[0].value.weight > 0 &&
            ranked[0].value.weight >= ranked[1].value.weight * AMBIGUITY_RATIO -> ranked[0].selected()
        else -> null
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
