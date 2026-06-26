package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.matcheckmobile.presentation.viewmodel.IntakeStagesCounts
import com.example.matcheckmobile.presentation.viewmodel.IntakeStagesViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.ui.theme.OpenSansFontFamily

private val TabletBreakpoint = 600.dp

// Зелёный дот-индикатор активности. Тёмный оттенок Material green читается
// на белом фоне кнопки без визуального шума.
private val ActiveDotColor = Color(0xFF2E7D32)
// Красный дот для просроченных строк (>2ч). Material red, читается на белом.
private val OverdueDotColor = Color(0xFFD32F2F)

/**
 * Стартовый экран приёмки: три равные кнопки выбора режима.
 *
 * - 1 Этап — выбор УПД с веб-портала и оформление приёмки (статус `filled`).
 * - 2 Этап — подтверждение МОЛ ранее оформленных приёмок (`filled → confirmed_mol`).
 * - Ручной внос — приёмка без УПД и без автотранспорта, инспектор сразу
 *   подтверждает её собой (создаётся со статусом `confirmed_mol`, на портале
 *   попадает в «Принятые / Подтверждено МОЛ» с тегом «Без документа»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeStagesScreen(
    onBack: () -> Unit,
    onStage1: () -> Unit,
    onStage2: () -> Unit,
    onManualEntry: () -> Unit,
    onArchive: () -> Unit,
) {
    val viewModel: IntakeStagesViewModel = matcheckViewModel()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Приёмка")
                        Text(
                            text = " / ",
                            color = Color(0xFF888888),
                        )
                        // «Архив» оформлен как clickable Text с подчёркиванием —
                        // визуально считывается как ссылка/кнопка, без отдельного
                        // Button-фрейма, который ломал бы баланс title-области.
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
            // В landscape отпускаем ограничение ширины — три кнопки в ряд должны
            // занять всю доступную ширину. В portrait — текущее поведение
            // (центрирование с максимумом 720dp на планшете).
            val contentMaxWidth: Dp = when {
                isLandscape -> maxWidth
                isTablet -> 720.dp
                else -> maxWidth
            }
            val outerPadding = if (isTablet) 32.dp else 16.dp
            val gap = if (isTablet) 24.dp else 16.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (isLandscape) {
                    // Landscape: три StageButton'а в один ряд, по трети
                    // ширины. Portrait-блок ниже не тронут — там Column как
                    // было до этой задачи.
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
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                        StageButton(
                            title = "2 Этап",
                            subtitle = "Автотранспорт",
                            description = "Описание: фотофиксация разгруженной машины, МОЛ, госномер",
                            counts = counts,
                            showStats = false,
                            onClick = onStage2,
                            isTablet = isTablet,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                        StageButton(
                            title = "Ручной внос",
                            subtitle = null,
                            description = "Описание: приёмка без автотранспорта. " +
                                "Инспектор сразу подтверждает её собой.",
                            counts = counts,
                            showStats = false,
                            onClick = onManualEntry,
                            isTablet = isTablet,
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
                            description = "Описание: фотофиксация разгруженной машины, МОЛ, госномер",
                            counts = counts,
                            showStats = false,
                            onClick = onStage2,
                            isTablet = isTablet,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        StageButton(
                            title = "Ручной внос",
                            subtitle = null,
                            description = "Описание: приёмка без автотранспорта. " +
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
    counts: IntakeStagesCounts,
    showStats: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean,
    subtitle: String? = null,
) {
    var infoDialogVisible by remember { mutableStateOf(false) }

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
                // Заголовок. На трёх кнопках высота меньше — слегка уменьшил
                // кегль (52sp на планшете, 42sp на телефоне) чтобы влезал и
                // оставалось место под subtitle и статы.
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = OpenSansFontFamily,
                    fontSize = if (isTablet) 52.sp else 42.sp,
                )
                // Подзаголовок — категория кнопки («Автотранспорт» для 1/2 Этапа).
                // Для «Ручной внос» subtitle=null — место занимает Spacer ниже,
                // чтобы выравнивание заголовков на всех трёх кнопках было одинаковым.
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = OpenSansFontFamily,
                        fontSize = if (isTablet) 22.sp else 18.sp,
                        color = Color(0xFF555555),
                    )
                }
                if (showStats) {
                    StageStats(counts = counts, isTablet = isTablet)
                } else {
                    // Заполнитель снизу — чтобы SpaceBetween не подтянул заголовок
                    // к низу кнопки. Высота не важна, важно само присутствие.
                    Spacer(Modifier.size(0.dp))
                }
            }

            // i-иконка в правом верхнем углу. Свой clickable IconButton'a
            // ловит тап и не пробрасывает его на onClick кнопки этапа.
            IconButton(
                onClick = { infoDialogVisible = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(if (isTablet) 56.dp else 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Информация",
                    tint = Color.Black,
                    modifier = Modifier.size(if (isTablet) 40.dp else 32.dp),
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
    counts: IntakeStagesCounts,
    isTablet: Boolean,
) {
    // Общая пульсация для всех точек — затухание/возврат прозрачности.
    // Один transition на все три ряда: точки мигают синхронно, без шума.
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

    val rowGap = if (isTablet) 6.dp else 4.dp
    val fontSize = if (isTablet) 24.sp else 20.sp
    val dotSize = if (isTablet) 14.dp else 11.dp
    val spacing = if (isTablet) 10.dp else 7.dp

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
            label = "В разгрузке:",
            value = counts.unloadingActive,
            fontSize = fontSize,
            dotSize = dotSize,
            spacing = spacing,
            dotAlpha = dotAlpha,
        )
        StatRow(
            label = "Разгрузка >2х часов:",
            value = counts.overdueUnloading,
            fontSize = fontSize,
            dotSize = dotSize,
            spacing = spacing,
            dotAlpha = dotAlpha,
            // Временно: красная точка всегда. Потом — динамически от значения.
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
