package com.example.matcheckmobile.domain.validation

/** Откуда в поле «Госномер» взялось текущее значение. */
enum class PlateOrigin {
    /** Набрано или исправлено инспектором. */
    MANUAL,

    /** Подставлено распознаванием без подтверждения. */
    OCR_AUTO,

    /** Подставлено по тапу инспектора из подсказки. */
    OCR_SUGGESTED,
}

/**
 * Предложенный номер, который инспектор подтверждает тапом.
 *
 * [attemptId] нужен телеметрии: он связывает событие «распознали» с событием
 * «подсказку приняли/проигнорировали», не таская за собой ни номер, ни путь к фото.
 */
data class PlateSuggestion(
    val text: String,
    val formatId: String,
    val attemptId: String,
)

/**
 * Устарел ли результат распознавания к моменту, когда он вернулся.
 *
 * Два снимка обрабатываются параллельно и заканчиваются в произвольном порядке. Без этой
 * проверки прочтение первого кадра перезаписало бы прочтение второго, а результат уже
 * удалённого фото всплыл бы после удаления.
 *
 * Чистая функция, потому что ViewModel в этом проекте юнит-тестом не покрыть: она
 * принимает конкретный AppContainer, поднимающий Room и Retrofit.
 */
fun isStaleOcrResult(
    attemptId: String,
    latestAttemptId: String?,
    photoPath: String,
    photoPaths: List<String>,
): Boolean = attemptId != latestAttemptId || photoPath !in photoPaths

/**
 * Пора ли записать «инспектор поправил распознанное».
 *
 * Событие пишется один раз на подстановку, а не на каждый введённый символ: иначе один
 * инспектор за смену забьёт журнал, из которого мы и собираемся считать долю ошибок.
 */
fun shouldReportPlateEdit(alreadyReported: Boolean, origin: PlateOrigin): Boolean =
    !alreadyReported && origin != PlateOrigin.MANUAL
