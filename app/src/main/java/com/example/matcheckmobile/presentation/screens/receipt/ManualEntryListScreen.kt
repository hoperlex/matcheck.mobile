package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.presentation.components.UpdTimerBadge
import com.example.matcheckmobile.presentation.viewmodel.ManualEntryListViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Список незавершённых черновиков «Ручной внос». Инспектор жмёт «Создать внос»
 * (создаётся локальный черновик и открывается форма), возвращается к любому
 * черновику тапом, удаляет свайпом слева-направо с подтверждением. По
 * «Завершить» на форме черновик уходит на сервер (confirmed_mol) и пропадает
 * из этого списка (виден в «Архиве»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryListScreen(
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val vm: ManualEntryListViewModel = matcheckViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    // Один тикер на весь экран — пересчитывает таймеры всех карточек.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ручной внос") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) {
                    Text(
                        text = "Нет незавершённых ручных приёмок",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(rows, key = { it.id }) { row ->
                            ManualDraftSwipeRow(
                                title = row.titleText,
                                subtitle = row.subtitleText,
                                timer = elapsedBadge(row.createdAt, nowMs),
                                onTap = { onOpen(row.id) },
                                onConfirmDelete = { vm.delete(row.id) },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { vm.createDraft(onCreate) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Создать внос", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Строка-черновик со свайпом слева-направо для удаления (образец —
 * SwipeableMaterialRow в MaterialsField). В отличие от него, удаление НЕ молча:
 * по достижении dismiss-state показываем подтверждение; отмена возвращает
 * карточку (`reset()`), подтверждение — вызывает удаление.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualDraftSwipeRow(
    title: String,
    subtitle: String,
    timer: UpdTimerBadge?,
    onTap: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                showConfirm = true
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) {
        UpdSummaryCard(
            title = title,
            subtitle = subtitle,
            onClick = onTap,
            started = false,
            timer = timer,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                scope.launch { dismissState.reset() }
            },
            title = { Text("Удалить черновик?") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onConfirmDelete()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch { dismissState.reset() }
                }) { Text("Отмена") }
            },
        )
    }
}

/** Бейдж «сколько черновик уже открыт» — elapsed от createdAt, без порога overdue. */
internal fun elapsedBadge(createdAt: Long, nowMs: Long): UpdTimerBadge {
    val durationMs = (nowMs - createdAt).coerceAtLeast(0L)
    return UpdTimerBadge(text = formatManualDraftDuration(durationMs), overdue = false)
}

/** Формат: «23мин», «1ч 23мин», «1д 1ч 23мин» (копия formatTimerDuration из Stage2ListScreen). */
internal fun formatManualDraftDuration(ms: Long): String {
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
