package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.AppUpdateState

/**
 * Постоянный индикатор «Установить обновление» в шапке main-экрана.
 *
 * Виден, когда AppUpdateRepository нашёл свежую версию на GitHub Releases
 * (state == [AppUpdateState.Available]) — даже если инспектор закрыл
 * AlertDialog кнопкой «Не сейчас». Тап на chip снова открывает диалог,
 * чтобы пользователь мог запустить скачивание, когда удобно.
 *
 * Когда обновление уже скачивается / готово к установке / нет обновления —
 * chip скрыт (статус показывает либо сам AlertDialog с прогрессом, либо
 * ничего рисовать не нужно).
 */
@Composable
fun AppUpdateChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as? MatcheckApplication ?: return
    val repo = app.container.appUpdateRepository
    val state by repo.state.collectAsState()

    val manifest = (state as? AppUpdateState.Available)?.manifest ?: return

    ElevatedAssistChip(
        onClick = { repo.reopenDialog() },
        modifier = modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = "Установить обновление приложения",
                modifier = Modifier.size(18.dp),
            )
        },
        label = {
            Text(
                text = "Установить обновление",
                style = MaterialTheme.typography.labelLarge,
            )
        },
        colors = AssistChipDefaults.elevatedAssistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
        ),
    )
}
