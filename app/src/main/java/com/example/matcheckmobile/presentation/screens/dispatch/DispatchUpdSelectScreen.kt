package com.example.matcheckmobile.presentation.screens.dispatch

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.domain.model.sourceDocTitle
import com.example.matcheckmobile.presentation.components.GroupHeaderCard
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.domain.model.docCountLabel
import com.example.matcheckmobile.domain.model.partyLabel
import com.example.matcheckmobile.domain.model.sourceDocGroupTitle
import com.example.matcheckmobile.presentation.viewmodel.DispatchUpdGroup
import com.example.matcheckmobile.presentation.viewmodel.DispatchUpdSelectViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val ContentMaxWidth = 720.dp

/** Зеркало [IntakeUpdSelectScreen] для отгрузки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchUpdSelectScreen(
    onBack: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
    onOpenWithGroup: (groupId: String) -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
    onCreateEmpty: () -> Unit,
) {
    val vm: DispatchUpdSelectViewModel = matcheckViewModel()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateWithLifecycle()
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTab by remember { mutableStateOf(DispatchUpdTab.Today) }
    val todayCount = remember(state) { state.today.sumOf { it.rows.size } }
    val futureCount = remember(state) { state.future.sumOf { it.rows.size } }
    val activeGroups = if (selectedTab == DispatchUpdTab.Today) state.today else state.future

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
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
                        Button(
                            onClick = onCreateEmpty,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                        ) {
                            Text("Создать отгрузку", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
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
                // См. комментарий в IntakeUpdSelectScreen: в landscape карточки
                // УПД от края до края, без боковых полей.
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
                        DispatchUpdTabSelector(
                            selected = selectedTab,
                            todayCount = todayCount,
                            futureCount = futureCount,
                            onSelect = { selectedTab = it },
                        )

                        if (activeGroups.isEmpty()) {
                            DispatchUpdEmptyState(tab = selectedTab)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                activeGroups.forEach { group ->
                                    val key = "${selectedTab.name}:${group.key}"
                                    val defaultExpanded = selectedTab == DispatchUpdTab.Today
                                    val expanded = expandedMap[key] ?: defaultExpanded
                                    item(key = "group:$key") {
                                        DispatchUpdGroupSection(
                                            group = group,
                                            expanded = expanded,
                                            onToggle = { expandedMap[key] = !expanded },
                                            onOpenWithUpd = onOpenWithUpd,
                                            onOpenWithGroup = onOpenWithGroup,
                                            onOpenDraft = onOpenDraft,
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

internal enum class DispatchUpdTab(val title: String) {
    Today(title = "Сегодня"),
    // См. комментарий в IntakeUpdSelectScreen.IntakeUpdTab: «Остальные» — это
    // всё кроме сегодняшней даты, включая просроченные и без даты поставки.
    Future(title = "Остальные"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DispatchUpdTabSelector(
    selected: DispatchUpdTab,
    todayCount: Int,
    futureCount: Int,
    onSelect: (DispatchUpdTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        val options = listOf(
            DispatchUpdTab.Today to todayCount,
            DispatchUpdTab.Future to futureCount,
        )
        options.forEachIndexed { index, (tab, count) ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(
                    text = "${tab.title} · $count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected == tab) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun DispatchUpdEmptyState(tab: DispatchUpdTab) {
    val title = when (tab) {
        DispatchUpdTab.Today -> "На сегодня исходящих УПД нет"
        DispatchUpdTab.Future -> "Будущих и недатированных УПД нет"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Можно начать пустую отгрузку — кнопка снизу.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DispatchUpdGroupSection(
    group: DispatchUpdGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
    onOpenWithGroup: (groupId: String) -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GroupHeaderCard(
            name = group.displayName,
            count = group.rows.size,
            expanded = expanded,
            onToggle = onToggle,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.rows.forEach { row ->
                    val doc = row.document
                    val title: String
                    val subtitle: String
                    val onClick: () -> Unit
                    if (doc != null) {
                        // Заголовок машины: «УПД 1403, 1404 · Накладная 192».
                        // Для одиночного документа вырождается в прежний
                        // sourceDocTitle — ветвиться не нужно.
                        title = sourceDocGroupTitle(row.documents)
                        // См. IntakeUpdSelectScreen: поставщик в шапке группы,
                        // в строке — грузополучатель (графа 4), прочерк если
                        // парсер её не распознал.
                        // Грузополучатель, иначе покупатель. Подрядчика не
                        // показываем — см. partyLabel.
                        val party = partyLabel(row.consigneeName, row.buyerName)
                        subtitle = if (row.documents.size > 1) {
                            "$party \u00b7 ${docCountLabel(row.documents.size)}"
                        } else {
                            party
                        }
                        val groupId = row.groupId
                        val draftId = row.draftId
                        // Начатый черновик открываем по его id — см.
                        // IntakeUpdSelectScreen, причина та же.
                        onClick = when {
                            draftId != null -> ({ onOpenDraft(draftId) })
                            groupId != null && row.documents.size > 1 ->
                                ({ onOpenWithGroup(groupId) })
                            else -> ({ onOpenWithUpd(doc.id) })
                        }
                    } else {
                        title = "Отгрузка без УПД"
                        subtitle = "Черновик ожидает завершения"
                        onClick = { row.draftId?.let(onOpenDraft) }
                    }
                    UpdSummaryCard(
                        title = title,
                        subtitle = subtitle,
                        onClick = onClick,
                        started = row.draftId != null,
                    )
                }
            }
        }
    }
}
