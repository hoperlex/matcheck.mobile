package com.example.matcheckmobile.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.matcheckmobile.domain.model.docCountLabel

/**
 * Состав машины изменился, пока форма 1 Этапа была открыта.
 *
 * Уведомление, а не вопрос: состав поставки определяет портал, и варианта
 * «оставить как было» у инспектора нет — такой машины на сервере уже не
 * существует. Позиции подтягиваются ДО показа этого диалога, здесь только
 * объяснение, что именно изменилось.
 *
 * Финализация при этом требует повторного нажатия «Завершить» — не чтобы
 * спросить согласия на состав, а чтобы человек увидел итоговый список прежде,
 * чем приёмка уйдёт на сервер.
 *
 * Слияние сохраняет введённое инспектором и переносит его правки на новые
 * строки документа — это возможно потому, что каждая позиция помнит своё
 * происхождение.
 *
 * Пустые [added] и [removed] одновременно — набор документов тот же, но внутри
 * них поменялись позиции или реквизиты (например, документ переразобрали).
 */
@Composable
fun GroupChangedDialog(
    added: Int,
    removed: Int,
    onDismiss: () -> Unit,
) {
    val details = buildList {
        if (added > 0) add("добавлено: ${docCountLabel(added)}")
        if (removed > 0) add("убрано: ${docCountLabel(removed)}")
    }
    val text = if (details.isEmpty()) {
        "Документы машины изменились на сервере. Позиции и суммы в форме " +
            "обновлены — проверьте список и завершите приёмку."
    } else {
        "В машине ${details.joinToString(", ")}. Позиции обновлены — проверьте " +
            "список и завершите приёмку."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Состав машины изменился") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понятно") }
        },
    )
}
