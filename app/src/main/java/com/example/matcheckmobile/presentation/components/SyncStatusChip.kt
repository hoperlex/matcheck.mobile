package com.example.matcheckmobile.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.ui.icons.LocalIcons

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
    isSyncing: Boolean,
    isOnline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Chip виден, пока есть что отправлять ИЛИ пока идёт активная отправка
    // (даже если pending уже опустился до 0, инспектору видна анимация —
    // подтверждение «sync прошёл и завершается»).
    if (pending <= 0 && !isSyncing) return

    val (icon, contentDesc) = if (isOnline) {
        LocalIcons.Sync to "Синхронизировать сейчас"
    } else {
        LocalIcons.CloudOff to "Ожидает сети"
    }

    // Бесконечное вращение иконки во время активного sync — спинить против
    // часовой стрелки (отрицательный угол), чтобы визуально читалось как
    // «отдаём данные», а не «принимаем». Период 1 секунда — стандартный
    // темп Material-индикаторов.
    val infiniteTransition = rememberInfiniteTransition(label = "sync-rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-rotation-angle",
    )

    val labelText = when {
        isSyncing -> "Синхронизация…"
        else -> "Синхронизировать ($pending)"
    }

    ElevatedAssistChip(
        onClick = onClick,
        modifier = modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                modifier = Modifier
                    .size(18.dp)
                    .then(if (isSyncing) Modifier.rotate(rotation) else Modifier),
            )
        },
        label = {
            Text(
                text = labelText,
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
