package com.example.matcheckmobile.presentation.screens.documents

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
import com.example.matcheckmobile.presentation.viewmodel.DocumentDetailsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ContentMaxWidth = 800.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailsScreen(onBack: () -> Unit) {
    val vm: DocumentDetailsViewModel = matcheckViewModel()
    val doc by vm.document.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Документ ${doc?.docNumber ?: ""}".trim()) },
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
                Column(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val current = doc
                    if (current == null) {
                        Text("Документ не найден")
                    } else {
                        Card {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("УПД ${current.docNumber ?: "—"}", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    text = "Дата: " + (current.docDate?.let { df.format(Date(it)) } ?: "—"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                current.totalSum?.let {
                                    Text("Сумма: %.2f ₽".format(it), style = MaterialTheme.typography.bodyMedium)
                                }
                                current.vatSum?.let {
                                    Text("НДС: %.2f ₽".format(it), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Статус: ${current.status.name}", style = MaterialTheme.typography.bodySmall)
                                Text("Источник: ${current.origin.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Позиции (${items.size})", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items, key = { it.localId }) { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("${item.lineNo}. ${item.nameRaw}", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${formatQty(item.qty)} ${item.unit}" +
                                                (item.sum?.let { " · %.2f ₽".format(it) } ?: ""),
                                            style = MaterialTheme.typography.bodyMedium,
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
}

private fun formatQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
