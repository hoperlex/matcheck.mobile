package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdRow
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdSelectViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeUpdSelectScreen(
    onBack: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
    onCreateEmpty: () -> Unit,
) {
    val vm: IntakeUpdSelectViewModel = matcheckViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выбор УПД для приёмки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
                        Button(
                            onClick = onCreateEmpty,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                        ) {
                            Text("Создать приёмку без УПД", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 32.dp),
                    ) {
                        Text(
                            "Нет входящих УПД для приёмки",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Можно начать пустую приёмку — кнопка снизу.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { it.document.localId }) { row ->
                            UpdRowCard(
                                row = row,
                                onClick = { onOpenWithUpd(row.document.localId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdRowCard(row: IntakeUpdRow, onClick: () -> Unit) {
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
