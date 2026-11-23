package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.domain.BusinessTime
import com.example.matcheckmobile.presentation.viewmodel.DraftVariant
import java.time.Instant
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/**
 * «На машине два начатых черновика»: менеджер объединил машины на портале, а
 * инспектор уже начинал оформлять обе карточки, и госномер, тип машины или
 * тексты в них разные. Склеивать такие значения нельзя (госномер не сложить),
 * поэтому инспектор выбирает основной вариант.
 *
 * Фото и материалы к этому моменту уже сложены — выбор касается только полей.
 * Невыбранные значения остаются в черновике до отправки. Закрыть без выбора
 * нельзя: иначе приёмка ушла бы с госномером, который никто не подтверждал.
 */
@Composable
fun DraftVariantsDialog(
    variants: List<DraftVariant>,
    vehicleLabel: (String?) -> String?,
    onChoose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("На машине два начатых черновика") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Машины объединили на портале. Фото и материалы сложены вместе. " +
                        "Выберите, какие госномер, тип машины и комментарий оставить:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (v in variants.sortedByDescending { it.updatedAt }) {
                    val time = runCatching {
                        Instant.ofEpochMilli(v.updatedAt).atZone(BusinessTime.ZONE).format(TIME)
                    }.getOrDefault("")
                    val lines = listOfNotNull(
                        "Изменён $time",
                        "Госномер: ${v.licensePlate.ifBlank { "—" }}",
                        "Тип машины: ${vehicleLabel(v.vehicleTypeCode) ?: "—"}",
                        v.commentText.takeIf(String::isNotBlank)?.let { "Комментарий: $it" },
                        v.manualUpdText.takeIf(String::isNotBlank)?.let { "Номер УПД: $it" },
                    )
                    OutlinedButton(
                        onClick = { onChoose(v.draftId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
