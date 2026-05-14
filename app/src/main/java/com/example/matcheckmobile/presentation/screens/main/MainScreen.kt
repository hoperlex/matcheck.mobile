package com.example.matcheckmobile.presentation.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.MainStatusViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onReceipt: () -> Unit,
    onDispatch: () -> Unit,
    onJournal: () -> Unit,
    onSyncQueue: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: MainStatusViewModel = matcheckViewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MatCheck КПП") })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusBar(
                pendingOps = status.pendingOperations,
                pendingAttachments = status.pendingAttachments,
                onClick = onSyncQueue,
            )
            BigActionButton(text = "Приёмка", onClick = onReceipt)
            BigActionButton(text = "Выезд", onClick = onDispatch)
            BigActionButton(text = "Журнал", onClick = onJournal, primary = false)
            BigActionButton(
                text = "Синхронизация" + if (status.pendingOperations + status.pendingAttachments > 0)
                    " (${status.pendingOperations + status.pendingAttachments})" else "",
                onClick = onSyncQueue,
                primary = false,
            )
            BigActionButton(text = "Настройки", onClick = onSettings, primary = false)
        }
    }
}

@Composable
private fun StatusBar(
    pendingOps: Int,
    pendingAttachments: Int,
    onClick: () -> Unit,
) {
    val total = pendingOps + pendingAttachments
    val color =
        if (total > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(0.dp),
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = color),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (total == 0) "Все данные синхронизированы"
                    else "Ожидают отправки: операций $pendingOps, фото $pendingAttachments",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (total > 0) Badge { Text(total.toString()) }
            }
        }
    }
}

@Composable
private fun BigActionButton(text: String, onClick: () -> Unit, primary: Boolean = true) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(text, style = MaterialTheme.typography.headlineSmall)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(text, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
