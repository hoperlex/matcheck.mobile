package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Временная заглушка для экрана «2 Этап». Будет заменена в шаге Г —
 * списком приёмок в статусе `filled` (ожидающих подтверждения МОЛ).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage2PlaceholderScreen(onBack: () -> Unit) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "В работе. Появится в следующем шаге.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
