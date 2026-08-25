package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.presentation.components.GroupHeaderCard
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.presentation.components.UpdTimerBadge
import com.example.matcheckmobile.presentation.viewmodel.Stage2ListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.delay
import java.time.Instant

private val ContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage2ListScreen(
    onBack: () -> Unit,
    onOpenDelivery: (String) -> Unit,
) {
    val vm: Stage2ListViewModel = matcheckViewModel()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Открытие экрана = свежая синхронизация: тянем изменения других планшетов
    // сразу, не дожидаясь SSE (в фоне рвётся) или периодики (15 мин). Дешёвый
    // pull; reconcile за ним троттлится. См. план «надёжная синхронизация».
    val context = LocalContext.current
    LaunchedEffect(Unit) { MatcheckSyncScheduler.requestImmediateSync(context) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    // Один общий тикер на весь экран — пересчитывает таймеры всех УПД сразу.
    // 30 секунд достаточно для шага «минута» в формате «1ч 23мин».
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    // Ошибку обновления показываем явно: молча оставленный старый список —
    // это ровно тот случай, когда на двух планшетах разные данные, а инспектор
    // об этом не знает.
    LaunchedEffect(refreshState.error) {
        val message = refreshState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeRefreshError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Выбор УПД для приёмки") },
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
        PullToRefreshBox(
            isRefreshing = refreshState.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val isLandscape = LocalConfiguration.current.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                val outerPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
                // В landscape: тот же layout, что у IntakeUpdSelect (1 Этап) —
                // карточки от левой границы до правой, без 720dp-потолка.
                // Иначе на планшете список приёмок 2 Этапа выглядит зажатым по
                // центру, тогда как 1 Этап на этом же экране уже full-width.
                val contentPadding = if (isLandscape) {
                    PaddingValues(
                        start = 0.dp,
                        end = 0.dp,
                        top = 8.dp,
                        bottom = outerPadding,
                    )
                } else {
                    PaddingValues(outerPadding)
                }
                val contentWidthModifier = if (isLandscape) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = contentWidthModifier,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (groups.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp),
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
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                groups.forEach { group ->
                                    // По умолчанию группа раскрыта — на 2 Этапе обычно
                                    // немного активных приёмок, и инспектору удобнее
                                    // видеть карточки сразу, без лишнего клика.
                                    val expanded = expandedMap[group.key] ?: true
                                    item(key = "group:${group.key}") {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            GroupHeaderCard(
                                                name = group.displayName,
                                                count = group.rows.size,
                                                expanded = expanded,
                                                onToggle = {
                                                    expandedMap[group.key] = !expanded
                                                },
                                            )
                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = expandVertically() + fadeIn(),
                                                exit = shrinkVertically() + fadeOut(),
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 8.dp, top = 8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    group.rows.forEach { row ->
                                                        UpdSummaryCard(
                                                            title = row.titleText,
                                                            subtitle = row.subtitleText,
                                                            onClick = { onOpenDelivery(row.delivery.id) },
                                                            // «Начато» скрыт — на 2 Этапе показываем таймер.
                                                            // row.hasDraft в VM считается, может пригодиться позже.
                                                            started = false,
                                                            timer = stage2TimerBadge(
                                                                arrivedAt = row.delivery.arrivedAt,
                                                                fallback = row.delivery.updatedAt,
                                                                nowMs = nowMs,
                                                            ),
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
                }
            }
        }
    }
}

private const val TWO_HOURS_MS: Long = 2L * 60L * 60L * 1000L

/**
 * Считает таймер «время в работе» для УПД 2 Этапа. Точка отсчёта — `arrivedAt`
 * (выставляется в Stage1FormViewModel.finalizeStage1 = момент Завершить 1 Этап).
 * Если по какой-то причине его нет (старая запись) — fallback на `updatedAt`.
 * Возвращает null когда обе метки невалидны.
 */
private fun stage2TimerBadge(arrivedAt: String?, fallback: String, nowMs: Long): UpdTimerBadge? {
    val startMs = parseInstantMs(arrivedAt) ?: parseInstantMs(fallback) ?: return null
    val durationMs = (nowMs - startMs).coerceAtLeast(0L)
    return UpdTimerBadge(
        text = formatTimerDuration(durationMs),
        overdue = durationMs >= TWO_HOURS_MS,
    )
}

private fun parseInstantMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/**
 * Формат: «23мин», «1ч 23мин», «1д 1ч 23мин». Минуты есть всегда; часы —
 * с момента, как накопился хотя бы 1ч; дни — с 24ч. Меньше минуты → «0мин».
 */
private fun formatTimerDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val days = totalMin / (24L * 60L)
    val hours = (totalMin % (24L * 60L)) / 60L
    val minutes = totalMin % 60L
    return buildString {
        if (days > 0L) append("${days}д ")
        if (days > 0L || hours > 0L) append("${hours}ч ")
        append("${minutes}мин")
    }
}
