package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.screens.receipt.ManualDraftSwipeRow
import com.example.matcheckmobile.presentation.screens.receipt.elapsedBadge
import com.example.matcheckmobile.presentation.viewmodel.ManualDispatchListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.delay

/**
 * Список незавершённых черновиков «Ручной вынос» (зеркало
 * [com.example.matcheckmobile.presentation.screens.receipt.ManualEntryListScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDispatchListScreen(
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val vm: ManualDispatchListViewModel = matcheckViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ручной вынос") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(72.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            modifier = Modifier.size(48.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) {
                    Text(
                        text = "Нет незавершённых ручных отгрузок",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(rows, key = { it.id }) { row ->
                            ManualDraftSwipeRow(
                                title = row.titleText,
                                subtitle = row.subtitleText,
                                timer = elapsedBadge(row.createdAt, nowMs),
                                onTap = { onOpen(row.id) },
                                onConfirmDelete = { vm.delete(row.id) },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { vm.createDraft(onCreate) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Создать вынос", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
