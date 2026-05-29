package com.example.matcheckmobile.presentation.screens.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.presentation.components.SyncStatusChip
import com.example.matcheckmobile.presentation.viewmodel.MainStatusViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.launch

private val TabletBreakpoint = 600.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onReceipt: () -> Unit,
    onDispatch: () -> Unit,
    onJournal: () -> Unit,
    onSyncQueue: () -> Unit,
    onSettings: () -> Unit,
    onDocuments: () -> Unit,
) {
    val vm: MainStatusViewModel = matcheckViewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    var showAdminSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Долгий тап на заголовке открывает служебное меню только в
                    // debug-сборках — инспектору в release-`matcheck` он не нужен,
                    // вся синхронизация и навигация доступны через явные элементы UI.
                    val titleModifier = if (BuildConfig.DEBUG) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showAdminSheet = true },
                        )
                    } else {
                        Modifier
                    }
                    Box(modifier = titleModifier) {
                        Text("su10")
                    }
                },
                actions = {
                    // Видимый индикатор очереди синхронизации: появляется, когда
                    // в Room есть неотправленные мутации/фото (был оффлайн или
                    // WorkManager ещё не успел проснуться). По тапу принудительно
                    // дёргаем sync прямо сейчас, не дожидаясь периодики.
                    SyncStatusChip(
                        pending = status.totalPending,
                        isSyncing = status.isSyncing,
                        // Без NetworkCallback (добавим отдельной задачей) считаем,
                        // что сеть есть — chip всегда трактуется как «можно синкнуть
                        // прямо сейчас». В оффлайне sync-Worker всё равно не упадёт,
                        // просто WorkManager отложит запуск до возврата сети.
                        isOnline = true,
                        onClick = {
                            MatcheckSyncScheduler.requestImmediateSync(context)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Запустил синхронизацию",
                                )
                            }
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    ActionButton(
                        text = "Приёмка",
                        onClick = onReceipt,
                        isTablet = isTablet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    ActionButton(
                        text = "Выезд",
                        onClick = onDispatch,
                        isTablet = isTablet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    PlanCard(
                        todayValue = status.expectedToday.toString(),
                        futureValue = status.expectedFuture.toString(),
                        isTablet = isTablet,
                    )
                }
            }
        }
    }

    if (showAdminSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun dismissAnd(action: () -> Unit) {
            scope.launch {
                sheetState.hide()
                showAdminSheet = false
                action()
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showAdminSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Служебное меню",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                ListItem(
                    headlineContent = { Text("Документы") },
                    modifier = Modifier.combinedClickable(
                        onClick = { dismissAnd(onDocuments) },
                        onLongClick = {},
                    ),
                )
                ListItem(
                    headlineContent = { Text("Журнал операций") },
                    modifier = Modifier.combinedClickable(
                        onClick = { dismissAnd(onJournal) },
                        onLongClick = {},
                    ),
                )
                ListItem(
                    headlineContent = { Text("Очередь синхронизации") },
                    supportingContent = {
                        val total = status.totalPending
                        if (total > 0) Text("Ожидают: $total")
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { dismissAnd(onSyncQueue) },
                        onLongClick = {},
                    ),
                )
                ListItem(
                    headlineContent = { Text("Настройки") },
                    modifier = Modifier.combinedClickable(
                        onClick = { dismissAnd(onSettings) },
                        onLongClick = {},
                    ),
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    todayValue: String,
    futureValue: String,
    isTablet: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (isTablet) 24.dp else 18.dp,
                vertical = if (isTablet) 18.dp else 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 14.dp else 10.dp),
        ) {
            Text(
                text = "План",
                style = if (isTablet)
                    MaterialTheme.typography.headlineSmall
                else
                    MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(if (isTablet) 24.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlanCell(
                    label = "Сегодня",
                    value = todayValue,
                    isTablet = isTablet,
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                PlanCell(
                    label = "Будущие",
                    value = futureValue,
                    isTablet = isTablet,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlanCell(
    label: String,
    value: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 6.dp else 4.dp),
    ) {
        Text(
            text = label,
            style = if (isTablet)
                MaterialTheme.typography.titleMedium
            else
                MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (isTablet)
                MaterialTheme.typography.displaySmall
            else
                MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(24.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(
            text = text,
            style = if (isTablet)
                MaterialTheme.typography.displayMedium
            else
                MaterialTheme.typography.displaySmall,
        )
    }
}
