package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.matcheckmobile.presentation.viewmodel.SavedReceiptRow
import com.example.matcheckmobile.presentation.viewmodel.SavedReceiptsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.ui.icons.LocalIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedReceiptsScreen(
    onBack: () -> Unit,
    onOpenReceipt: (sessionLocalId: String) -> Unit,
) {
    val vm: SavedReceiptsViewModel = matcheckViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сохранённые приёмки") },
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
                        "Сохранённых приёмок нет",
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
                        items(rows, key = { it.session.localId }) { row ->
                            SavedReceiptCard(
                                row = row,
                                onClick = { onOpenReceipt(row.session.localId) },
                            )
                        }
                    }
                }
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.updNumber?.let { "УПД $it" } ?: "Без УПД",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (row.session.confirmedByMol) {
                    MolBadge()
                }
            }
            Text(
                text = row.siteName ?: "Объект не указан",
                style = MaterialTheme.typography.bodyLarge,
            )
            val party = row.contractorName ?: row.supplierName
            if (!party.isNullOrEmpty()) {
                Text(text = party, style = MaterialTheme.typography.bodyMedium)
            }
            val ts = row.session.finalizedAt ?: row.session.startedAt
            Text(
                text = "Сохранено: ${df.format(Date(ts))} · ГРЗ ${row.session.vehicleNumber}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MolBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(24.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            imageVector = LocalIcons.Verified,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "МОЛ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
