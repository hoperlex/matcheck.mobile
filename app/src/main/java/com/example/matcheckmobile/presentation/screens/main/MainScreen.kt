package com.example.matcheckmobile.presentation.screens.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.presentation.viewmodel.MainStatusViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showAdminSheet = true },
                        ),
                    ) {
                        Text("matcheck")
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
                    StatusBanner(
                        pendingOps = status.pendingOperations,
                        pendingSessions = status.pendingSessions,
                        pendingAttachments = status.pendingAttachments,
                    )

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
                }
            }
        }
    }

    if (showAdminSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
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
private fun StatusBanner(pendingOps: Int, pendingSessions: Int, pendingAttachments: Int) {
    val total = pendingOps + pendingSessions + pendingAttachments
    val container = if (total == 0)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val onContainer = if (total == 0)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer
    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (total == 0)
                "Все данные синхронизированы"
            else
                "Ожидают отправки: приёмок $pendingSessions, операций $pendingOps, фото $pendingAttachments",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center,
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
