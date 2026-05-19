package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val TabletBreakpoint = 600.dp

/**
 * Стартовый экран приёмки: две большие кнопки выбора этапа.
 *
 * - 1 Этап — выбор УПД с веб-портала и оформление приёмки (статус `filled`).
 * - 2 Этап — подтверждение МОЛ ранее оформленных приёмок (`filled → confirmed_mol`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeStagesScreen(
    onBack: () -> Unit,
    onStage1: () -> Unit,
    onStage2: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Приёмка") },
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
            val contentMaxWidth: Dp = if (isTablet) 900.dp else maxWidth
            val outerPadding = if (isTablet) 32.dp else 16.dp
            val buttonHeight = if (isTablet) 176.dp else 96.dp
            val gap = if (isTablet) 24.dp else 16.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    if (isTablet) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            StageButton(
                                text = "1 Этап",
                                onClick = onStage1,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(buttonHeight),
                                isTablet = true,
                            )
                            StageButton(
                                text = "2 Этап",
                                onClick = onStage2,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(buttonHeight),
                                isTablet = true,
                            )
                        }
                    } else {
                        StageButton(
                            text = "1 Этап",
                            onClick = onStage1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(buttonHeight),
                            isTablet = false,
                        )
                        StageButton(
                            text = "2 Этап",
                            onClick = onStage2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(buttonHeight),
                            isTablet = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(
            text = text,
            style = if (isTablet)
                MaterialTheme.typography.headlineMedium
            else
                MaterialTheme.typography.headlineSmall,
        )
    }
}
