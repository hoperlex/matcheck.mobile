package com.example.matcheckmobile.presentation.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.SettingsViewModel

/**
 * Второй барьер выхода: показывается только когда на планшете осталась
 * неотправленная работа. Обычное подтверждение «Выйти?» его не заменяет —
 * там инспектор соглашается выйти, а не потерять свои приёмки и фото.
 *
 * Один и тот же диалог на MainScreen и SettingsScreen: обе кнопки «Выйти»
 * ведут в [SettingsViewModel.requestLogout], который сам решает, показывать
 * ли предупреждение.
 */
@Composable
fun LogoutGuardDialog(vm: SettingsViewModel) {
    val logoutError by vm.logoutError.collectAsStateWithLifecycle()
    logoutError?.let { message ->
        // Очистка не удалась — сессия НАМЕРЕННО осталась живой: данные ещё на
        // планшете, и отправить их можно только под текущим токеном.
        AlertDialog(
            onDismissRequest = vm::consumeLogoutError,
            title = { Text("Выход отменён") },
            text = { Text("$message\n\nДанные остались на планшете, вы по-прежнему в системе.") },
            confirmButton = {
                TextButton(onClick = vm::consumeLogoutError) { Text("Понятно") }
            },
        )
        return
    }

    val pending by vm.pendingBeforeLogout.collectAsStateWithLifecycle()
    val work = pending ?: return

    AlertDialog(
        onDismissRequest = vm::cancelLogout,
        title = { Text("Данные ещё не отправлены") },
        text = {
            Column {
                Text("Не доехало до сервера: ${work.describe()}.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "При выходе эти данные будут удалены с планшета. " +
                        "Сначала попробуйте отправить их на сервер.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            // При одном лишь карантине отправлять нечего — сервер откажет
            // снова; кнопка бессмысленна и только запутала бы.
            if (work.syncCanHelp) {
                TextButton(onClick = vm::syncBeforeLogout) { Text("Отправить и выйти") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = vm::cancelLogout) { Text("Отмена") }
                TextButton(onClick = vm::confirmLogoutDiscardingData) {
                    Text("Выйти без отправки", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
