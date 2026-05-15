package com.example.matcheckmobile.presentation.screens.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.DocumentRow
import com.example.matcheckmobile.presentation.viewmodel.DocumentsViewModel
import com.example.matcheckmobile.presentation.viewmodel.SavedReceiptRow
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TabletBreakpoint = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenReceipt: (String) -> Unit,
) {
    val vm: DocumentsViewModel = matcheckViewModel()
    val docs by vm.rows.collectAsStateWithLifecycle()
    val receipts by vm.savedReceiptRows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Документы и приёмки") },
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
            val isTablet = maxWidth >= TabletBreakpoint
            val outerPadding = if (isTablet) 24.dp else 16.dp
            if (isTablet) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        DocumentsSection(docs = docs, onOpen = onOpenDocument)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        SavedReceiptsSection(rows = receipts, onOpen = onOpenReceipt)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    DocumentsSection(docs = docs, onOpen = onOpenDocument, scrollable = false)
                    SavedReceiptsSection(rows = receipts, onOpen = onOpenReceipt, scrollable = false)
                }
            }
        }
    }
}

@Composable
private fun DocumentsSection(
    docs: List<DocumentRow>,
    onOpen: (String) -> Unit,
    scrollable: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("УПД", style = MaterialTheme.typography.titleLarge)
        if (docs.isEmpty()) {
            Text(
                "Документов пока нет",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else if (scrollable) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(docs, key = { it.document.localId }) { row ->
                    DocumentCard(row, onClick = { onOpen(row.document.localId) })
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                docs.forEach { row ->
                    DocumentCard(row, onClick = { onOpen(row.document.localId) })
                }
            }
        }
    }
}

@Composable
private fun SavedReceiptsSection(
    rows: List<SavedReceiptRow>,
    onOpen: (String) -> Unit,
    scrollable: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Сохранённые приёмки", style = MaterialTheme.typography.titleLarge)
        if (rows.isEmpty()) {
            Text(
                "Сохранённых приёмок нет",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else if (scrollable) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.session.localId }) { row ->
                    SavedReceiptCard(row, onClick = { onOpen(row.session.localId) })
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    SavedReceiptCard(row, onClick = { onOpen(row.session.localId) })
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(row: DocumentRow, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "УПД ${row.document.docNumber ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = row.supplierName ?: "Поставщик не указан",
                style = MaterialTheme.typography.bodyMedium,
            )
            val date = row.document.docDate?.let { df.format(Date(it)) } ?: "—"
            val sum = row.document.totalSum?.let { "%.2f ₽".format(it) } ?: "—"
            Text(
                text = "$date · $sum",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SavedReceiptCard(row: SavedReceiptRow, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = row.updNumber?.let { "УПД $it" } ?: "Без УПД",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = row.siteName ?: "Объект не указан",
                style = MaterialTheme.typography.bodyMedium,
            )
            val contractor = row.contractorName ?: row.supplierName
            if (!contractor.isNullOrEmpty()) {
                Text(
                    text = contractor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val ts = row.session.finalizedAt ?: row.session.startedAt
            Text(
                text = "Сохранено: ${df.format(Date(ts))} · ГРЗ ${row.session.vehicleNumber}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
