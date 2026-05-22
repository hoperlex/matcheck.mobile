package com.example.matcheckmobile.presentation.screens.receipt

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.components.ContractorHeaderCard
import com.example.matcheckmobile.presentation.components.UpdSummaryCard
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdGroup
import com.example.matcheckmobile.presentation.viewmodel.IntakeUpdSelectViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val ContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeUpdSelectScreen(
    onBack: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
    onCreateEmpty: () -> Unit,
) {
    val vm: IntakeUpdSelectViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTab by remember { mutableStateOf(IntakeUpdTab.Today) }
    val todayCount = remember(state) { state.today.sumOf { it.rows.size } }
    val futureCount = remember(state) { state.future.sumOf { it.rows.size } }
    val activeGroups = if (selectedTab == IntakeUpdTab.Today) state.today else state.future

    Scaffold(
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
                            Text("Создать приёмку", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                    IntakeUpdTabSelector(
                        selected = selectedTab,
                        todayCount = todayCount,
                        futureCount = futureCount,
                        onSelect = { selectedTab = it },
                    )

                    if (activeGroups.isEmpty()) {
                        IntakeUpdEmptyState(tab = selectedTab)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            activeGroups.forEach { group ->
                                val expanded = expandedMap[group.contractorName] == true
                                item(key = "group:${selectedTab.name}:${group.contractorName}") {
                                    IntakeUpdGroupSection(
                                        group = group,
                                        expanded = expanded,
                                        onToggle = {
                                            expandedMap[group.contractorName] = !expanded
                                        },
                                        onOpenWithUpd = onOpenWithUpd,
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

internal enum class IntakeUpdTab(val title: String) {
    Today(title = "Сегодня"),
    Future(title = "Будущие"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntakeUpdTabSelector(
    selected: IntakeUpdTab,
    todayCount: Int,
    futureCount: Int,
    onSelect: (IntakeUpdTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val options = listOf(
            IntakeUpdTab.Today to todayCount,
            IntakeUpdTab.Future to futureCount,
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
private fun IntakeUpdEmptyState(tab: IntakeUpdTab) {
    val title = when (tab) {
        IntakeUpdTab.Today -> "На сегодня входящих УПД нет"
        IntakeUpdTab.Future -> "Будущих и недатированных УПД нет"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Можно начать пустую приёмку — кнопка снизу.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IntakeUpdGroupSection(
    group: IntakeUpdGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenWithUpd: (updLocalId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ContractorHeaderCard(
            name = group.contractorName,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.rows.forEach { row ->
                    UpdSummaryCard(
                        title = "УПД ${row.document.docNumber ?: "—"}",
                        subtitle = "Поставщик: ${row.supplierName ?: "—"}",
                        onClick = { onOpenWithUpd(row.document.id) },
                    )
                }
            }
        }
    }
}
