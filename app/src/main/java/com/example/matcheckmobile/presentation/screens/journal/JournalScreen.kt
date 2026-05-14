package com.example.matcheckmobile.presentation.screens.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.domain.model.OperationType
import com.example.matcheckmobile.domain.model.SyncStatus
import com.example.matcheckmobile.presentation.viewmodel.JournalViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm: JournalViewModel = matcheckViewModel()
    val items by vm.operations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал операций") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Журнал пуст", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.localId }) { op ->
                    OperationRow(op, onClick = { onOpen(op.localId) })
                }
            }
        }
    }
}

@Composable
private fun OperationRow(op: MaterialOperationEntity, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${typeLabel(op.type)} · ${op.materialNameRaw}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${formatQty(op.quantity)} ${op.unit}" +
                    (op.vehicleNumber?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = df.format(Date(op.createdAtLocal)) + " · " + statusLabel(op.syncStatus),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun typeLabel(t: OperationType): String = when (t) {
    OperationType.RECEIPT -> "Приёмка"
    OperationType.DISPATCH -> "Выезд"
    OperationType.CORRECTION -> "Корректировка"
}

internal fun statusLabel(s: SyncStatus): String = when (s) {
    SyncStatus.PENDING -> "ожидает"
    SyncStatus.SYNCING -> "отправляется"
    SyncStatus.SYNCED -> "синхронизировано"
    SyncStatus.ERROR -> "ошибка"
    SyncStatus.NEEDS_REVIEW -> "проверка"
}

internal fun formatQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
