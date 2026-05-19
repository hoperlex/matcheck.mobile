package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.presentation.viewmodel.Stage2ListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage2ListScreen(
    onBack: () -> Unit,
    onOpenDelivery: (String) -> Unit,
) {
    val vm: Stage2ListViewModel = matcheckViewModel()
    val deliveries by vm.deliveries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("2 Этап") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (deliveries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Нет приёмок, ожидающих подтверждения",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(deliveries, key = { it.id }) { delivery ->
                    DeliveryCard(delivery = delivery, onClick = { onOpenDelivery(delivery.id) })
                }
            }
        }
    }
}

@Composable
private fun DeliveryCard(delivery: RemoteDeliveryEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Приёмка #${delivery.id.take(8)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Создана: ${formatTimestamp(delivery.createdAt)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!delivery.comment.isNullOrBlank()) {
                Text(
                    text = delivery.comment,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun formatTimestamp(iso: String): String {
    // Простое усечение ISO-8601 до «yyyy-MM-dd HH:mm» — детальное форматирование
    // оставим для UI-полирования. Если строка не соответствует формату — возвращаем как есть.
    val safe = iso.replace('T', ' ')
    val cut = safe.indexOf(':').takeIf { it > 0 }?.let { safe.substring(0, minOf(safe.length, 16)) }
    return cut ?: iso
}
