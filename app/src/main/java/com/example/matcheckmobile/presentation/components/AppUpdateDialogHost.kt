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

        // APK скачан, но нет разрешения «Установка неизвестных приложений».
        // Установщик уже открыл нужный системный экран; здесь даём явный
        // диалог: после включения тумблера — «Установить» (без перекачки),
        // либо «Настройки», если экран потерялся.
        is AppUpdateState.InstallBlocked -> AlertDialog(
            onDismissRequest = { repo.dismiss() },
            title = { Text("Нужно разрешение на установку") },
            text = {
                Text(
                    "Разрешите «Установка неизвестных приложений» для su10, " +
                        "затем нажмите «Установить».",
                )
            },
            confirmButton = {
                TextButton(onClick = { repo.installNow(s.manifest, s.apkFile) }) {
                    Text("Установить")
                }
            },
            dismissButton = {
                TextButton(onClick = { repo.openInstallSettings() }) {
                    Text("Настройки")
                }
            },
        )

        // Ошибка скачивания/запуска установки. Показываем всегда (пользователь
        // сам нажал «Установить» и ждёт результат) с кнопкой «Повторить» —
        // чтобы не переоткрывать приложение по 10 раз при флапающей сети/CDN.
        is AppUpdateState.DownloadFailed -> AlertDialog(
            onDismissRequest = { repo.dismiss() },
            title = { Text("Не удалось обновить") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { repo.startDownload(s.manifest) }) {
                    Text("Повторить")
                }
            },
            dismissButton = {
                TextButton(onClick = { repo.dismiss() }) {
                    Text("Закрыть")
                }
            },
        )

        // Failed — тихая ошибка ФОНОВОЙ проверки версии (таймаут на GitHub,
        // нет сети при cold start). Инспектору показывать бесполезно — следующая
        // проверка сама подтянет. UI чистый, причина видна в Logcat.
        is AppUpdateState.Failed -> Unit

        else -> Unit
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return "%.1f МБ".format(mb)
}
