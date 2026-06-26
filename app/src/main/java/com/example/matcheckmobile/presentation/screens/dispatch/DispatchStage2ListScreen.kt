package com.example.matcheckmobile.presentation.screens.dispatch

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
import com.example.matcheckmobile.presentation.components.ContractorHeaderCard
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.presentation.components.UpdTimerBadge
import com.example.matcheckmobile.presentation.viewmodel.DispatchStage2ListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.delay
import java.time.Instant

private val ContentMaxWidth = 720.dp

/** Зеркало [com.example.matcheckmobile.presentation.screens.receipt.Stage2ListScreen] для отгрузки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchStage2ListScreen(
    onBack: () -> Unit,
    onOpenShipment: (String) -> Unit,
) {
    val vm: DispatchStage2ListViewModel = matcheckViewModel()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выбор УПД для отгрузки") },
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isLandscape = LocalConfiguration.current.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            val outerPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
            // См. комментарий в Stage2ListScreen: в landscape карточки
            // от края до края, без 720dp-потолка, как и на 1 Этапе.
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
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = contentWidthModifier,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Нет отгрузок, ожидающих подтверждения",
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
                                val expanded = expandedMap[group.contractorName] ?: true
                                item(key = "group:${group.contractorName}") {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        ContractorHeaderCard(
                                            name = group.contractorName,
                                            count = group.rows.size,
                                            expanded = expanded,
                                            onToggle = {
                                                expandedMap[group.contractorName] = !expanded
                                            },
                                        )
                                        AnimatedVisibility(
                                            visible = expanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(start = 8.dp, top = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                group.rows.forEach { row ->
                                                    UpdSummaryCard(
                                                        title = row.titleText,
                                                        subtitle = row.subtitleText,
                                                        onClick = { onOpenShipment(row.shipment.id) },
                                                        started = false,
                                                        timer = stage2TimerBadge(
                                                            shippedAt = row.shipment.shippedAt,
                                                            fallback = row.shipment.updatedAt,
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

private const val TWO_HOURS_MS: Long = 2L * 60L * 60L * 1000L

private fun stage2TimerBadge(shippedAt: String?, fallback: String, nowMs: Long): UpdTimerBadge? {
    val startMs = parseInstantMs(shippedAt) ?: parseInstantMs(fallback) ?: return null
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
