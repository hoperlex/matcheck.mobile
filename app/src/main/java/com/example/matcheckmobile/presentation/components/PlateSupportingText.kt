package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.matcheckmobile.domain.validation.PlateOrigin
import com.example.matcheckmobile.domain.validation.PlateSuggestion

/**
 * Подпись под полем «Госномер»: либо пометка о распознавании, либо подсказка с тапом.
 *
 * Два уровня появились потому, что строгое «подставлять только при полном совпадении двух
 * прочтений» отсекало вместе с ошибками и годные номера — инспекторы сообщали, что
 * распознавание срабатывает через раз. Теперь неуверенный результат не пропадает, а
 * предлагается: тап вставляет его в обычное поле, где номер можно поправить посимвольно.
 *
 * Поле при этом остаётся обычным `OutlinedTextField` — подсказка ничего не блокирует, и
 * незамеченная подсказка не мешает набрать номер руками.
 */
fun plateSupportingText(
    origin: PlateOrigin,
    suggestion: PlateSuggestion?,
    onApply: () -> Unit,
): (@Composable () -> Unit)? = when {
    suggestion != null -> {
        {
            Text(
                text = "Похоже на ${suggestion.text} — подставить",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onApply),
            )
        }
    }
    origin != PlateOrigin.MANUAL -> {
        { Text("Распознано с фото — проверьте") }
    }
    else -> null
}
