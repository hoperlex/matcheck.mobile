package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            val contentMaxWidth: Dp = if (isTablet) 720.dp else maxWidth
            val outerPadding = if (isTablet) 32.dp else 16.dp
            val gap = if (isTablet) 24.dp else 16.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    StageButton(
                        title = "1 Этап",
                        description = "Описание: фотофиксация госномера, груза, документов",
                        onClick = onStage1,
                        isTablet = isTablet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    StageButton(
                        title = "2 Этап",
                        description = "Описание: фотофиксация разгруженной машины, МОЛ, госномер",
                        onClick = onStage2,
                        isTablet = isTablet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StageButton(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        ),
        border = BorderStroke(2.dp, Color.Black),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                style = if (isTablet)
                    MaterialTheme.typography.displayMedium
                else
                    MaterialTheme.typography.displaySmall,
            )
            Text(
                text = description,
                style = if (isTablet)
                    MaterialTheme.typography.bodyLarge
                else
                    MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
