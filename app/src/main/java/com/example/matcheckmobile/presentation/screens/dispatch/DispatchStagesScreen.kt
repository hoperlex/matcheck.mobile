package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.DispatchStagesViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.ui.theme.OpenSansFontFamily

private val TabletBreakpoint = 600.dp

// Цвета пульсирующих точек — те же, что на IntakeStagesScreen, чтобы оба
// экрана выглядели согласованно.
private val ActiveDotColor = Color(0xFF2E7D32)
private val OverdueDotColor = Color(0xFFD32F2F)

/**
 * Стартовый экран отгрузки. UI идентичен [IntakeStagesScreen], отличается
 * только заголовок («Отгрузка» вместо «Приёмка»). Счётчики на кнопке
 * «1 Этап» — «Всего / В отгрузке / Отгрузка >2ч» — приходят из
 * [DispatchStagesViewModel] (зеркало IntakeStagesViewModel для outbound:
 * direction='outbound' документы + shipment-drafts + status='shipped').
 */
data class DispatchStagesCounts(
    val totalToday: Int = 0,
    val unloadingActive: Int = 0,
    val overdueUnloading: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchStagesScreen(
    onBack: () -> Unit,
    onStage1: () -> Unit,
    onStage2: () -> Unit,
    onManualEntry: () -> Unit,
    onArchive: () -> Unit,
) {
    val viewModel: DispatchStagesViewModel = matcheckViewModel()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Отгрузка")
                        Text(
                            text = " / ",
                            color = Color(0xFF888888),
                        )
                        // Стиль ссылки тот же, что в IntakeStagesScreen «Приёмка / Архив»:
                        // primary color + underline + clickable.
                        Text(
                            text = "Архив",
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(onClick = onArchive)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                },
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
            val isLandscape = LocalConfiguration.current.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            val contentMaxWidth: Dp = when {
                isLandscape -> maxWidth
                isTablet -> 720.dp
                else -> maxWidth
            }
            val outerPadding = if (isTablet) 32.dp else 16.dp
            val gap = if (isTablet) 24.dp else 16.dp
            // См. комментарий в IntakeStagesScreen: в landscape кнопки идут
            // от края до края.
            val outerPaddingValues = if (isLandscape) {
                PaddingValues(
                    start = 0.dp,
                    end = 0.dp,
                    top = outerPadding,
                    bottom = outerPadding,
                )
            } else {
                PaddingValues(outerPadding)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPaddingValues),
                contentAlignment = Alignment.Center,
            ) {
                if (isLandscape) {
                    // Landscape: три StageButton'а в один ряд по трети ширины.
                    // Portrait-ветка ниже не тронута.
                    Row(
                        modifier = Modifier
                            .widthIn(max = contentMaxWidth)
                            .fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        StageButton(
                            title = "1 Этап",
                            subtitle = "Автотранспорт",
                            description = "Описание: фотофиксация госномера, груза, документов",
                            counts = counts,
                            showStats = true,
                            onClick = onStage1,
                            isTablet = isTablet,
                            isLandscape = true,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                        StageButton(
                            title = "2 Этап",
                            subtitle = "Автотранспорт",
                            description = "Описание: фотофиксация выгрузки, МОЛ, госномер",
                            counts = counts,
                            showStats = false,
                            onClick = onStage2,
                            isTablet = isTablet,
                            isLandscape = true,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                        StageButton(
                            title = "Ручной вынос",
                            subtitle = null,
                            description = "Описание: отгрузка без автотранспорта. " +
                                "Инспектор сразу подтверждает её собой.",
                            counts = counts,
                            showStats = false,
                            onClick = onManualEntry,
                            isTablet = isTablet,
                            isLandscape = true,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = contentMaxWidth)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        StageButton(
                            title = "1 Этап",
                            subtitle = "Автотранспорт",
                            description = "Описание: фотофиксация госномера, груза, документов",
                            counts = counts,
                            showStats = true,
                            onClick = onStage1,
                            isTablet = isTablet,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        StageButton(
                            title = "2 Этап",
                            subtitle = "Автотранспорт",
                            description = "Описание: фотофиксация выгрузки, МОЛ, госномер",
                            counts = counts,
                            showStats = false,
                            onClick = onStage2,
                            isTablet = isTablet,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        StageButton(
                            title = "Ручной вынос",
                            subtitle = null,
                            description = "Описание: отгрузка без автотранспорта. " +
                                "Инспектор сразу подтверждает её собой.",
                            counts = counts,
                            showStats = false,
                            onClick = onManualEntry,
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
}

@Composable
private fun StageButton(
    title: String,
    description: String,
    counts: DispatchStagesCounts,
    showStats: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean,
    isLandscape: Boolean = false,
    subtitle: String? = null,
) {
    var infoDialogVisible by remember { mutableStateOf(false) }

    val titleSize = when {
        isLandscape && isTablet -> 40.sp
        isLandscape -> 28.sp
        isTablet -> 52.sp
        else -> 42.sp
    }
    val titleLineHeight = when {
        isLandscape && isTablet -> 46.sp
        isLandscape -> 32.sp
        isTablet -> 60.sp
        else -> 50.sp
    }
    val subtitleSize = when {
        isLandscape && isTablet -> 16.sp
        isLandscape -> 13.sp
        isTablet -> 22.sp
        else -> 18.sp
    }
    val contentPad = if (isLandscape) 12.dp else 24.dp
    val infoBtnSize = when {
        isLandscape && isTablet -> 40.dp
        isLandscape -> 32.dp
        isTablet -> 56.dp
        else -> 44.dp
    }
    val infoIconSize = when {
        isLandscape && isTablet -> 24.dp
        isLandscape -> 20.dp
        isTablet -> 40.dp
        else -> 32.dp
    }
    val infoOffset = if (isLandscape) 4.dp else 12.dp

    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(contentPad),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        ),
        border = BorderStroke(2.dp, Color.Black),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // См. комментарий в IntakeStagesScreen.StageButton: в landscape
            // опускаем текстовый блок ниже верха кнопки на infoBtnSize,
            // чтобы «Ручной вынос» не накладывался на i-IconButton.
            val textTopPad = if (isLandscape) infoBtnSize else 0.dp
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = textTopPad),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = OpenSansFontFamily,
                        fontSize = titleSize,
                        lineHeight = titleLineHeight,
                        maxLines = 2,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontFamily = OpenSansFontFamily,
                            fontSize = subtitleSize,
                            color = Color(0xFF555555),
                            maxLines = 1,
                        )
                    }
                }
                if (showStats) {
                    StageStats(
                        counts = counts,
                        isTablet = isTablet,
                        isLandscape = isLandscape,
                    )
                } else {
                    Spacer(Modifier.size(0.dp))
                }
            }

            IconButton(
                onClick = { infoDialogVisible = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = infoOffset, y = -infoOffset)
                    .size(infoBtnSize),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Информация",
                    tint = Color.Black,
                    modifier = Modifier.size(infoIconSize),
                )
            }
        }
    }

    if (infoDialogVisible) {
        AlertDialog(
            onDismissRequest = { infoDialogVisible = false },
            title = {
                Text(
                    text = title,
                    fontFamily = OpenSansFontFamily,
                    fontSize = if (isTablet) 28.sp else 24.sp,
                )
            },
            text = {
                Text(
                    text = description,
                    fontFamily = OpenSansFontFamily,
                    fontSize = if (isTablet) 22.sp else 18.sp,
                    lineHeight = if (isTablet) 30.sp else 26.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { infoDialogVisible = false }) {
                    Text(
                        text = "Закрыть",
                        fontSize = if (isTablet) 20.sp else 16.sp,
                    )
                }
            },
        )
    }
}

@Composable
private fun StageStats(
    counts: DispatchStagesCounts,
    isTablet: Boolean,
    isLandscape: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "stats-pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stats-dot-alpha",
    )

    val rowGap = when {
        isLandscape -> 2.dp
        isTablet -> 6.dp
        else -> 4.dp
    }
    val fontSize = when {
        isLandscape && isTablet -> 16.sp
        isLandscape -> 12.sp
        isTablet -> 24.sp
        else -> 20.sp
    }
    val dotSize = when {
        isLandscape -> 8.dp
        isTablet -> 14.dp
        else -> 11.dp
    }
    val spacing = when {
        isLandscape -> 4.dp
        isTablet -> 10.dp
        else -> 7.dp
    }

    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        StatRow(
            label = "Всего:",
            value = counts.totalToday,
            fontSize = fontSize,
            dotSize = dotSize,
            spacing = spacing,
            dotAlpha = dotAlpha,
        )
        StatRow(
            label = "В отгрузке:",
            value = counts.unloadingActive,
            fontSize = fontSize,
            dotSize = dotSize,
            spacing = spacing,
            dotAlpha = dotAlpha,
        )
        StatRow(
            label = "Отгрузка >2х часов:",
            value = counts.overdueUnloading,
            fontSize = fontSize,
            dotSize = dotSize,
            spacing = spacing,
            dotAlpha = dotAlpha,
            dotColor = OverdueDotColor,
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: Int,
    fontSize: TextUnit,
    dotSize: Dp,
    spacing: Dp,
    dotAlpha: Float,
    dotColor: Color = ActiveDotColor,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label $value",
            fontFamily = OpenSansFontFamily,
            fontSize = fontSize,
        )
        Spacer(Modifier.width(spacing))
        Box(
            modifier = Modifier
                .size(dotSize)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}
