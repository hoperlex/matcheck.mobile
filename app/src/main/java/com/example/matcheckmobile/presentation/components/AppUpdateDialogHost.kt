package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.AppUpdateState

/**
 * Глобальный overlay-диалог обновления приложения. Размещается один раз в
 * корне activity (см. MainActivity.setContent). Наблюдает за
 * AppUpdateRepository.state и показывает соответствующий диалог:
 *
 *   Idle / Checking / UpToDate — ничего не показывает.
 *   Available     — «Доступна версия X — Установить / Не сейчас».
 *   Downloading   — прогресс-бар (тап «вне» не закрывает, чтобы не оборвать).
 *   ReadyToInstall — closer'нет диалог автоматически: системный installer
 *                    уже открыт, наш UI не нужен.
 *   Failed        — короткое уведомление, можно закрыть.
 *
 * Инспектор не имеет доступа к ручной проверке (нет кнопки «обновить» в
 * UI приложения): проверки запускаются автоматически при cold start и раз
 * в 6 часов через AppUpdateWorker.
 */
@Composable
fun AppUpdateDialogHost() {
    val context = LocalContext.current
    val app = context.applicationContext as? MatcheckApplication ?: return
    val repo = app.container.appUpdateRepository
    val state by repo.state.collectAsState()

    when (val s = state) {
        is AppUpdateState.Available -> if (s.dialogShown) AlertDialog(
            onDismissRequest = { repo.dismiss() },
            title = { Text("Доступно обновление") },
            text = {
                Column {
                    Text("Версия ${s.manifest.versionName}", style = MaterialTheme.typography.titleMedium)
                    if (s.manifest.changelog.isNotBlank()) {
                        Text(
                            s.manifest.changelog,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    s.manifest.apkSizeBytes?.let { bytes ->
                        Text(
                            "Размер: ${formatSize(bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { repo.startDownload(s.manifest) }) {
                    Text("Установить")
                }
            },
            dismissButton = {
                TextButton(onClick = { repo.dismiss() }) {
                    Text("Не сейчас")
                }
            },
        )

        is AppUpdateState.Downloading -> AlertDialog(
            onDismissRequest = { /* downloading — не даём закрыть случайным тапом */ },
            title = { Text("Загрузка обновления") },
            text = {
                Column {
                    Text("Версия ${s.manifest.versionName}")
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Text(
                        "${s.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = { /* пусто, скрываем кнопку */ },
        )

        // Failed — это техническая ошибка фоновой проверки (таймаут на GitHub,
        // нет сети при cold start и т.п.). Инспектору показывать «Не удалось
        // обновить» бесполезно — он не понимает контекст («что не удалось?»),
        // а следующая проверка через 6ч сама всё подтянет. Тихо игнорируем —
        // state остаётся Failed внутри Repository (можно посмотреть в Logcat
        // для диагностики), но UI чистый. Также не показываем при download-
        // ошибке — там пользователь сам тапнет «Установить» ещё раз через chip.
        is AppUpdateState.Failed -> Unit

        else -> Unit
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return "%.1f МБ".format(mb)
}
