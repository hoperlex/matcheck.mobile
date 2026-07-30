package com.example.matcheckmobile.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.SettingsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var serverUrl by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(state.serverBaseUrl) { serverUrl = state.serverBaseUrl }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReadOnlyRow("ID устройства", state.deviceId)
            ReadOnlyRow("Текущий пользователь", state.userId)
            ReadOnlyRow("Текущий объект", state.siteId)
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL сервера (для будущей интеграции)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { vm.setServerBaseUrl(serverUrl.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Полный logout: чистит токены, syncCursor и Room (см.
            // SettingsViewModel.logout). После него MatcheckNavHost
            // автоматически уйдёт на LoginScreen через SessionEvent.LoggedOut.
            Button(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                )
                Text(
                    text = "  Выйти из аккаунта",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (confirmLogout) {
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text("Выйти из аккаунта?", fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "Локальные данные (УПД, приёмки, очередь, незавершённые драфты) " +
                            "будут удалены с устройства. После повторного входа всё подтянется " +
                            "заново с сервера.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmLogout = false
                            vm.logout()
                        },
                    ) {
                        Text(
                            "Выйти",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
                },
            )
        }

        // Второй барьер: выход при непустой очереди. Появляется сам, если
        // requestLogout нашёл неотправленное.
        LogoutGuardDialog(vm)
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}
