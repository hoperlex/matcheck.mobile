package com.example.matcheckmobile.presentation.screens.details

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.screens.journal.statusLabel
import com.example.matcheckmobile.presentation.screens.journal.typeLabel
import com.example.matcheckmobile.presentation.viewmodel.OperationDetailsViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationDetailsScreen(onBack: () -> Unit) {
    val vm: OperationDetailsViewModel = matcheckViewModel()
    val op by vm.operation.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val photoStorage =
        remember { (context.applicationContext as MatcheckApplication).container.photoStorage }
    var pendingPath by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPath
        if (success && path != null) vm.addPhoto(path)
        pendingPath = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Детали операции") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        val current = op
        if (current == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            ) { Text("Операция не найдена") }
            return@Scaffold
        }
        val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(typeLabel(current.type), style = MaterialTheme.typography.headlineSmall)
            if (current.sessionLocalId != null) {
                Text(
                    "в составе приёмки",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(current.materialNameRaw, style = MaterialTheme.typography.titleMedium)
            Text("${current.quantity} ${current.unit}", style = MaterialTheme.typography.bodyLarge)
            current.vehicleNumber?.let { Text("Машина: $it") }
            current.driverName?.let { Text("Водитель: $it") }
            current.comment?.let { Text("Комментарий: $it") }
            Text(df.format(Date(current.createdAtLocal)))
            Text("Статус: ${statusLabel(current.syncStatus)}")
            current.serverId?.let { Text("ID на сервере: $it", style = MaterialTheme.typography.bodySmall) }
            current.lastSyncError?.let {
                Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = {
                    val file = photoStorage.createTempFile()
                    val uri: Uri = photoStorage.toContentUri(file)
                    pendingPath = file.absolutePath
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("  Добавить фото", style = MaterialTheme.typography.titleMedium)
            }
            Text("Фото (${attachments.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.localId }) { a ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                a.localFilePath.substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${a.attachmentType.name} · ${a.uploadStatus.name}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            a.remoteUrl?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
