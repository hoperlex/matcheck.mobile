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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.presentation.components.AppUpdateChip
import com.example.matcheckmobile.presentation.components.SyncStatusChip
import com.example.matcheckmobile.presentation.viewmodel.MainStatusViewModel
import com.example.matcheckmobile.presentation.viewmodel.SettingsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.ui.theme.OswaldFontFamily
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
    var confirmLogout by remember { mutableStateOf(false) }
    val settingsVm: SettingsViewModel = matcheckViewModel()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val prefersLandscape by settingsVm.prefersLandscape.collectAsStateWithLifecycle()
    // Landscape-режим — отдельный layout: тумблер уезжает в topbar справа,
    // кнопки «Въезд»/«Выезд» становятся в ряд. Portrait — без изменений, всё
    // как видели инспекторы до этой задачи. Detection через LocalConfiguration:
    // флаг preference и фактическая ориентация могут расходиться (system
    // Android 16 sw600dp+ может игнорировать requestedOrientation).
    val isLandscape = LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Тап по «su10» открывает диалог выхода из аккаунта — чтобы
                    // инспектор мог разлогиниться и зайти под другим логином прямо
                    // с главного экрана (проверить, какие УПД подтянутся под другим
                    // аккаунтом). Долгий тап в debug-сборке открывает служебное меню;
                    // в release он не нужен — навигация доступна через явный UI.
                    val titleModifier = Modifier.combinedClickable(
                        onClick = { confirmLogout = true },
                        onLongClick = if (BuildConfig.DEBUG) {
                            { showAdminSheet = true }
                        } else {
                            null
                        },
                    )
                    Box(modifier = titleModifier) {
                        // Брендовая надпись «МАТБАЛАНС»: Oswald — узкий
                        // заголовочный шрифт от Google Fonts, грузится через
                        // GMS-провайдер при первом запуске и кэшируется. При
                        // недоступности (нет Play Services / сети при cold
                        // start) Compose откатывается на системный sans-serif,
                        // текст всё равно отрендерится.
                        Text(
                            text = "МАТБАЛАНС",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 26.sp,
                        )
                    }
                },
                actions = {
                    // Видимый индикатор «Установить обновление»: появляется,
                    // когда AppUpdateRepository нашёл свежую версию на GitHub
                    // Releases. Тап — открывает AlertDialog с changelog'ом
                    // (см. AppUpdateDialogHost), оттуда инспектор запустит
                    // скачивание. После «Не сейчас» chip остаётся, чтобы
                    // инспектор мог вернуться к диалогу когда удобно.
                    AppUpdateChip()
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
                    // В landscape тумблер перенесён в topbar (правее SyncChip)
                    // и без подписи — место в landscape ценнее. В portrait он
                    // остаётся под PlanCard с подписью.
                    if (isLandscape) {
                        Switch(
                            checked = prefersLandscape,
                            onCheckedChange = settingsVm::setPrefersLandscape,
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        )
                    }
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
                    if (isLandscape) {
                        // Landscape: «Въезд» и «Выезд» в одну строку, группа
                        // занимает 80% ширины (требование UX). Высота — общая
                        // вершинная зона через weight(1f) на Row.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .align(Alignment.CenterHorizontally)
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            ActionButton(
                                text = "Въезд",
                                onClick = onReceipt,
                                isTablet = isTablet,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f),
                            )
                            ActionButton(
                                text = "Выезд",
                                onClick = onDispatch,
                                isTablet = isTablet,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f),
                            )
                        }
                    } else {
                        ActionButton(
                            text = "Въезд",
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
                    }
                    PlanCard(
                        todayValue = status.expectedToday.toString(),
                        futureValue = status.expectedFuture.toString(),
                        isTablet = isTablet,
                    )
                    // Тумблер «Горизонтальный режим». Хранится локально
                    // (DeviceSettings.prefersLandscape). MainActivity подписана
                    // на этот флаг и переключает requestedOrientation. Внимание:
                    // на больших экранах с Android 16 / targetSdk≥36 система
                    // может игнорировать requestedOrientation — это ожидаемо.
                    // В landscape тумблер живёт в topbar (см. блок actions выше).
                    if (!isLandscape) {
                        OrientationToggleRow(
                            checked = prefersLandscape,
                            onCheckedChange = settingsVm::setPrefersLandscape,
                        )
                    }
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

    if (confirmLogout) {
        // Полный logout: чистит токены, syncCursor и Room (см.
        // SettingsViewModel.logout). После него MatcheckNavHost автоматически
        // уйдёт на LoginScreen через SessionEvent.LoggedOut — можно сразу
        // войти под другим логином.
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?", fontWeight = FontWeight.SemiBold) },
            text = {
                if (settingsState.userEmail.isNotEmpty()) {
                    Text("Вы вошли как ${settingsState.userEmail}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        settingsVm.logout()
                    },
                ) {
                    Text(
                        "Выйти",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun OrientationToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Горизонтальный режим",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
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
                    // «Остальные» — УПД с любой `expectedDate` кроме сегодняшней
                    // (включая просроченные и без даты). См. логику в
                    // MainStatusViewModel.expectedCounts.
                    label = "Остальные",
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
