package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.IntakeStagesViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.ui.theme.OpenSansFontFamily

private val TabletBreakpoint = 600.dp

// Палитра для бейджа активных УПД. Зелёные оттенки выровнены под Material green:
// тёмная заливка дота читается на светло-зелёном фоне Surface, тёмно-зелёный
// текст даёт достаточный контраст без визуального шума на белой карточке.
private val ActiveDotColor = Color(0xFF2E7D32)
private val ActiveBadgeBackground = Color(0xFFE8F5E9)
private val ActiveBadgeBorder = Color(0xFF66BB6A)
private val ActiveBadgeText = Color(0xFF1B5E20)

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
    val viewModel: IntakeStagesViewModel = matcheckViewModel()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Приёмка") },
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
                        activeCount = counts.stage1Active,
                        onClick = onStage1,
                        isTablet = isTablet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    StageButton(
                        title = "2 Этап",
                        description = "Описание: фотофиксация разгруженной машины, МОЛ, госномер",
                        activeCount = counts.stage2Active,
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
    activeCount: Int,
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start,
            ) {
                // Заголовок ×1.5 от исходного displayMedium/displaySmall, центрируется
                // на всю ширину кнопки.
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = OpenSansFontFamily,
                    fontSize = if (isTablet) 68.sp else 54.sp,
                )
                // Описание ×2 от исходного bodyLarge/bodyMedium, слева снизу.
                Text(
                    text = description,
                    fontFamily = OpenSansFontFamily,
                    fontSize = if (isTablet) 32.sp else 28.sp,
                    lineHeight = if (isTablet) 46.sp else 40.sp,
                )
            }
            if (activeCount > 0) {
                ActiveCountBadge(
                    count = activeCount,
                    isTablet = isTablet,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun ActiveCountBadge(
    count: Int,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    // Пульсация дота — затухание/возврат прозрачности, бесконечный цикл.
    // Reverse + длительность ~1100мс даёт «живое» дыхание без отвлекающего мерцания.
    val transition = rememberInfiniteTransition(label = "active-pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )

    val dotSize = if (isTablet) 14.dp else 11.dp
    val fontSize = if (isTablet) 22.sp else 18.sp
    val horizontalPadding = if (isTablet) 14.dp else 10.dp
    val verticalPadding = if (isTablet) 8.dp else 6.dp
    val spacing = if (isTablet) 10.dp else 7.dp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = ActiveBadgeBackground,
        border = BorderStroke(1.dp, ActiveBadgeBorder),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(ActiveDotColor),
            )
            Spacer(Modifier.width(spacing))
            Text(
                text = "$count активн.",
                fontFamily = OpenSansFontFamily,
                fontSize = fontSize,
                color = ActiveBadgeText,
            )
        }
    }
}
