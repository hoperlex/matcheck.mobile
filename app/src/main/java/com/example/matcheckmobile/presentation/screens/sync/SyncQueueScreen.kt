package com.example.matcheckmobile.presentation.screens.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.screens.journal.statusLabel
import com.example.matcheckmobile.presentation.screens.journal.typeLabel
import com.example.matcheckmobile.presentation.viewmodel.SyncQueueViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncQueueScreen(onBack: () -> Unit, onOpenOperation: (String) -> Unit) {
    val vm: SyncQueueViewModel = matcheckViewModel()
    val ops by vm.pendingOperations.collectAsStateWithLifecycle()
    val photos by vm.pendingAttachments.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Очередь синхронизации") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = vm::retryNow,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text("Запустить синхронизацию сейчас", style = MaterialTheme.typography.titleMedium)
            }
            Text("Операции (${ops.size})", style = MaterialTheme.typography.titleMedium)
            if (ops.isEmpty()) {
                Text("Нет операций в очереди", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ops, key = { it.localId }) { op ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenOperation(op.localId) },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${typeLabel(op.type)} · ${op.materialNameRaw}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "статус: ${statusLabel(op.syncStatus)}" +
                                        (op.lastSyncError?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Фото (${photos.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (photos.isEmpty()) {
                Text("Нет фото в очереди", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.localId }) { p ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    p.localFilePath.substringAfterLast('/'),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${p.uploadStatus.name}" +
                                        (p.lastUploadError?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
