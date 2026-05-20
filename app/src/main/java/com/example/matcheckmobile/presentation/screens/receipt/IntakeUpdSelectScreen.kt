package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdGroup
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdRow
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdSelectViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val ContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeUpdSelectScreen(
    onBack: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
    onCreateEmpty: () -> Unit,
) {
    val vm: IntakeUpdSelectViewModel = matcheckViewModel()
    val groups by vm.groups.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выбор УПД для приёмки") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            modifier = Modifier.size(32.dp),
                        )
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
                        .navigationBarsPadding()
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
                            Text("Создать приёмку", style = MaterialTheme.typography.titleLarge)
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
                Column(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (groups.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
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
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groups.forEach { group ->
                                item(key = "header:${group.contractorName}") {
                                    ContractorHeader(name = group.contractorName)
                                }
                                items(group.rows, key = { it.document.id }) { row ->
                                    UpdRowCard(
                                        row = row,
                                        onClick = { onOpenWithUpd(row.document.id) },
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

@Composable
private fun ContractorHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun UpdRowCard(row: IntakeUpdRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "УПД ${row.document.docNumber ?: "—"}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Поставщик: ${row.supplierName ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
