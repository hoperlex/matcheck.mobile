package com.example.matcheckmobile.domain.model

/**
 * Префикс заголовка документа для UI. Сервер возвращает `kind`:
 *  - 'upd'                — Универсальный передаточный документ;
 *  - 'transport_waybill'  — печатная транспортная накладная (ТН-2116);
 *  - 'os2_transfer'       — накладная на внутреннее перемещение ОС (ОС-2);
 *  - 'request'            — заявка/письмо.
 *
 * Веб-портал на той же модели рисует «УПД» либо «Накладная» — мобила
 * должна повторять, иначе инспектор видит «УПД Д000002518» там, где на
 * портале это «Накладная Д000002518».
 *
 * Используется на экранах выбора УПД/накладной, в шапках формы Stage 2,
 * в карточках Документов и в pretty-формате привязанных документов в
 * списках 2 Этапа.
 */
fun sourceDocTitlePrefix(kind: String?): String = when (kind) {
    "upd" -> "УПД"
    "transport_waybill", "os2_transfer" -> "Накладная"
    "request" -> "Заявка"
    else -> "Документ"
}

/** Полный заголовок: `«УПД Д000002518»` / `«Накладная Д000002518»`. */
fun sourceDocTitle(kind: String?, docNumber: String?): String {
    val prefix = sourceDocTitlePrefix(kind)
    val number = docNumber?.takeIf { it.isNotBlank() } ?: "—"
    return "$prefix $number"
}
