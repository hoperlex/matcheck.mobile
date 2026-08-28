package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.domain.model.docCountLabel
import com.example.matcheckmobile.presentation.viewmodel.GroupDocumentLabel

/**
 * В машине появился документ уже ПОСЛЕ того, как приёмку оформили.
 *
 * Состав приёмки фиксируется на 1 Этапе и на 2 Этапе не меняется: здесь МОЛ
 * подтверждает то, что инспектор принял у машины. Дописать материалы позднего
 * документа в подтверждаемую приёмку значило бы изменить принятое количество
 * задним числом, поэтому приложение только предупреждает.
 *
 * Плашка, а не диалог: диалог при открытии формы закрывают не читая, а этот
 * текст должен оставаться перед глазами, пока МОЛ подтверждает приёмку.
 *
 * Показывается ТОЛЬКО при непустом [docs] — молчание здесь дороже лишнего
 * шума: расхождение бывает раз в несколько недель.
 *
 * @param operationWord как назвать операцию в тексте: «приёмке» либо «отгрузке».
 *   Форма одна на оба потока, а машина и там и там называется машиной.
 */
@Composable
fun LateDocumentsNotice(
    docs: List<GroupDocumentLabel>,
    operationWord: String,
    modifier: Modifier = Modifier,
) {
    if (docs.isEmpty()) return
    // Номера через запятую; документ без распознанного номера показываем как
    // «б/н», иначе в строке появилось бы слово «null».
    val numbers = docs.joinToString(", ") { it.number?.takeIf(String::isNotBlank) ?: "б/н" }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "В машине появился ${docCountLabel(docs.size)}: $numbers",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Материалов этого документа в $operationWord нет — она была оформлена " +
                    "раньше. Подтверждение их не учтёт. Если материалы приняты, сообщите менеджеру.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
