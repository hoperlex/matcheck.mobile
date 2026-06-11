package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Чекбокс «Транзит» на 1 этапе Приёмки/Выезда. Используется в picked-UPD
 * сценарии (внутри VehicleTypeChips trailingHeader) и в empty-draft
 * (отдельной строкой ниже комментария).
 */
@Composable
fun TransitCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = "Транзит", style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Чекбокс «ОС» (основные средства) на 1 этапе Приёмки/Выезда. Рисуется
 * рядом с Транзитом. Поле сохраняется в Stage1Draft, отправляется в
 * репозиторий, на сервер (миграция 0065), на веб-портале показывается
 * тегом «📦 ОС» в шапке карточки.
 */
@Composable
fun AssetsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = "ОС", style = MaterialTheme.typography.labelLarge)
    }
}
