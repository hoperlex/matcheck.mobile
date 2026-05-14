package com.example.matcheckmobile.presentation.screens.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.example.matcheckmobile.presentation.viewmodel.DocumentRow
import com.example.matcheckmobile.presentation.viewmodel.DocumentsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ContentMaxWidth = 800.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm: DocumentsViewModel = matcheckViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Документы") },
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
            val outerPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (rows.isEmpty()) {
                    Text(
                        "Документов пока нет",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { it.document.localId }) { row ->
                            DocumentCard(row, onClick = { onOpen(row.document.localId) })
                        }
                    }
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
