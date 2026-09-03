package com.example.matcheckmobile.domain.validation

/** Допустимые символы: латиница, кириллица, цифры, пробел и дефис; длина 1–12. */
private val ALLOWED_RE = Regex("^[A-ZА-Я0-9 -]{1,12}$")

/**
 * Российский ГОСТ Р 50577 (легковые/грузовые): 1 буква, 3 цифры, 2 буквы, 2–3 цифры региона.
 * Принимаем оба написания (кириллица и визуально совпадающая латиница).
 *
 * `internal`, а не `private`: этот же шаблон — единственный фильтр для номеров,
 * распознанных с фото (см. PlateParsing.kt). Дублировать regex нельзя, иначе две копии
 * разъедутся.
 */
internal val RU_RE = Regex(
    "^[ABEKMHOPCTYXАВЕКМНОРСТУХ]\\d{3}[ABEKMHOPCTYXАВЕКМНОРСТУХ]{2}\\d{2,3}$"
)

/** Приводим к верхнему регистру; внутреннюю структуру (пробелы) не трогаем. */
fun normalizeVehiclePlate(input: String): String = input.uppercase()

/**
 * Мягкая подсказка под полем — не блокирует сохранение.
 *  - пустая строка → подсказки нет;
 *  - совпадает с ГОСТ РФ → «Похоже на российский номер»;
 *  - есть запрещённые символы → «Используйте буквы, цифры, пробел, дефис»;
 *  - длина > 12 → «Слишком длинно для номера ТС»;
 *  - всё остальное → «Проверьте формат» (мягкое предупреждение).
 */
fun vehiclePlateHint(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val upper = trimmed.uppercase()
    val compact = upper.replace(" ", "")
    if (RU_RE.matches(compact)) return "Похоже на российский номер"
    if (upper.length > 12) return "Слишком длинно для номера ТС"
    if (!ALLOWED_RE.matches(upper)) {
        return "Используйте буквы, цифры, пробел, дефис"
    }
    return "Проверьте формат номера"
}
