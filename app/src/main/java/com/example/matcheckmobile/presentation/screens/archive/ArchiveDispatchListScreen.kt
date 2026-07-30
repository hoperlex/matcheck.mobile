package com.example.matcheckmobile.presentation.screens.archive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.presentation.components.ContractorHeaderCard
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.presentation.util.formatLocalTime
import com.example.matcheckmobile.presentation.viewmodel.ArchiveDispatchListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val ContentMaxWidth = 720.dp

/**
 * Архив завершённых отгрузок за последнюю неделю — UI зеркалит
 * [ArchiveIntakeListScreen]: даты вместо контрагентов, кликабельные карточки
 * с переходом в детальный экран по `shipmentId`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveDispatchListScreen(
    onBack: () -> Unit,
    onOpenDetail: (shipmentId: String) -> Unit,
) {
    val vm: ArchiveDispatchListViewModel = matcheckViewModel()
    // Открытие экрана = свежая синхронизация: тянем изменения других планшетов
    // сразу, не дожидаясь SSE (в фоне рвётся) или периодики (15 мин). Дешёвый
    // pull; reconcile за ним троттлится. См. план «надёжная синхронизация».
    val context = LocalContext.current
    LaunchedEffect(Unit) { MatcheckSyncScheduler.requestImmediateSync(context) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val expandedMap = remember { mutableStateMapOf<Long, Boolean>() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Ошибку синхронизации показываем явно — см. ArchiveIntakeListScreen.
    LaunchedEffect(refreshState.error) {
        val message = refreshState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeRefreshError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Архив отгрузок") },
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
                val outerPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth(),
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
                                    text = "За последнюю неделю отгрузок нет",
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
                                    val isToday = isTodayLocal(group.dayStartMs)
                                    val expanded = expandedMap[group.dayStartMs] ?: isToday
                                    item(key = "day:${group.dayStartMs}") {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            ContractorHeaderCard(
                                                name = group.dateLabel,
                                                count = group.rows.size,
                                                expanded = expanded,
                                                onToggle = {
                                                    expandedMap[group.dayStartMs] = !expanded
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
                                                        val shippedTxt = formatLocalTime(row.shippedAtMs)
                                                        val confirmedTxt = formatLocalTime(row.confirmedAtMs)
                                                        val timeLine = buildString {
                                                            append("Госномер: ").append(row.vehiclePlate)
                                                            if (shippedTxt != null) append("  ·  выезд ").append(shippedTxt)
                                                            if (confirmedTxt != null) append("  ·  подтв. ").append(confirmedTxt)
                                                        }
                                                        UpdSummaryCard(
                                                            title = row.updNumber,
                                                            subtitle = timeLine,
                                                            onClick = { onOpenDetail(row.shipment.id) },
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

/**
 * «Сегодня» — по бизнес-зоне объекта, а не по настройкам планшета. Иначе
 * группа считалась бы московской датой (см. BusinessTime в ViewModel), а
 * подсветка «сегодня» — датой устройства, и они расходились бы.
 */
private fun isTodayLocal(dayStartMs: Long): Boolean =
    com.example.matcheckmobile.domain.BusinessTime.isToday(dayStartMs)
