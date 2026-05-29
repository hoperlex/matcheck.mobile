package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Индикатор очереди синхронизации в шапке main-экрана.
 *
 * Логика отображения:
 *  - [pending] == 0 → chip скрыт (родитель не должен его вообще вставлять).
 *  - online + pending > 0 → синяя «🔄 N» — синк-Worker сам отправит, можно
 *    тапнуть, чтобы дёрнуть прямо сейчас.
 *  - offline + pending > 0 → серая «📡✕ N» — ждём, пока появится связь;
 *    тап показывает короткое поясняющее сообщение.
 *
 * Сам тап обрабатывает родитель (MainScreen): запускает
 * MatcheckSyncScheduler.requestImmediateSync() и показывает Snackbar.
 */
@Composable
fun SyncStatusChip(
    pending: Int,
    isOnline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending <= 0) return

    val label = pending.toString()
    val (icon, contentDesc) = if (isOnline) {
        Icons.Default.CloudSync to "Синхронизировать сейчас"
    } else {
        Icons.Default.CloudOff to "Ожидает сети"
    }

    ElevatedAssistChip(
        onClick = onClick,
        modifier = modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                modifier = Modifier.size(18.dp),
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        colors = AssistChipDefaults.elevatedAssistChipColors(
            containerColor = if (isOnline)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (isOnline)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconContentColor = if (isOnline)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
