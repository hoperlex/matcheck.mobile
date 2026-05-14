package com.example.matcheckmobile.presentation.screens.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.screens.journal.statusLabel
import com.example.matcheckmobile.presentation.screens.journal.typeLabel
import com.example.matcheckmobile.presentation.viewmodel.SyncQueueViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncQueueScreen(onBack: () -> Unit, onOpenOperation: (String) -> Unit) {
    val vm: SyncQueueViewModel = matcheckViewModel()
    val sessions by vm.pendingSessions.collectAsStateWithLifecycle()
    val ops by vm.pendingOperations.collectAsStateWithLifecycle()
    val photos by vm.pendingAttachments.collectAsStateWithLifecycle()
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val outer = if (maxWidth >= 600.dp) 32.dp else 16.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outer),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 800.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Button(
                            onClick = vm::retryNow,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text(
                                "Запустить синхронизацию сейчас",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    item {
                        Text(
                            "Приёмки (${sessions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (sessions.isEmpty()) {
                        item {
                            Text("Нет приёмок в очереди", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(sessions, key = { it.localId }) { s ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Приёмка · ${s.vehicleNumber}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        df.format(Date(s.startedAt)) + " · " + statusLabel(s.syncStatus),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    s.lastSyncError?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "Операции (${ops.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (ops.isEmpty()) {
                        item {
                            Text("Нет операций в очереди", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(ops, key = { it.localId }) { op ->
                            Card(modifier = Modifier.fillMaxWidth()) {
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
                    item {
                        Text(
                            "Фото (${photos.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (photos.isEmpty()) {
                        item {
                            Text("Нет фото в очереди", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(photos, key = { it.localId }) { p ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        p.localFilePath.substringAfterLast('/'),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        p.uploadStatus.name +
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
}
