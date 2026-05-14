package com.example.matcheckmobile.presentation.screens.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.viewmodel.OperationFormViewModel

private val FormMaxWidth = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationFormScreen(
    title: String,
    viewModel: OperationFormViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val photoStorage =
        remember { (context.applicationContext as MatcheckApplication).container.photoStorage }
    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPhotoPath
        if (success && path != null) {
            viewModel.addPhotoPath(path)
        }
        pendingPhotoPath = null
    }

    LaunchedEffect(state.savedOperationId) {
        if (state.savedOperationId != null) {
            viewModel.resetSaved()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            val isTablet = maxWidth >= FormMaxWidth
            val outerPadding = if (isTablet) 32.dp else 16.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = FormMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.materialName,
                        onValueChange = viewModel::setMaterialName,
                        label = { Text("Наименование материала") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.quantityText,
                            onValueChange = viewModel::setQuantity,
                            label = { Text("Количество") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.unit,
                            onValueChange = viewModel::setUnit,
                            label = { Text("Ед.") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = state.vehicleNumber,
                        onValueChange = viewModel::setVehicleNumber,
                        label = { Text("Госномер") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.comment,
                        onValueChange = viewModel::setComment,
                        label = { Text("Комментарий") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedButton(
                        onClick = {
                            val file = photoStorage.createTempFile()
                            val uri: Uri = photoStorage.toContentUri(file)
                            pendingPhotoPath = file.absolutePath
                            cameraLauncher.launch(uri)
                        },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Text("  Добавить фото", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.pendingPhotoPaths.isNotEmpty()) {
                        Text(
                            "Прикреплено фото: ${state.pendingPhotoPaths.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                            LazyColumn(modifier = Modifier.fillMaxHeight()) {
                                items(state.pendingPhotoPaths) { path ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = path.substringAfterLast('/'),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    state.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text(
                            if (state.isSubmitting) "Сохранение..." else "Сохранить",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
    }
}
