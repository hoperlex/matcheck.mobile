package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.MaterialsField
import com.example.matcheckmobile.presentation.components.ModalTextField
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.PhotoThumb
import com.example.matcheckmobile.presentation.components.VehicleTypeChips
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.viewmodel.Stage1FormViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val TabletBreakpoint = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage1FormScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val vm: Stage1FormViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var previewPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.finalized) {
        if (state.finalized) onFinalized()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    val takeDocumentPhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::onDocumentPhotoTaken,
    )
    val takeCargoPhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::onCargoPhotoTaken,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая приёмка") },
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
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(snackbarData = data) } },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val isTablet = maxWidth >= TabletBreakpoint
            val contentMaxWidth: Dp = if (isTablet) 900.dp else maxWidth
            val outerPadding = if (isTablet) 24.dp else 16.dp
            val sectionGap = if (isTablet) 20.dp else 14.dp
            val photoButtonHeight = if (isTablet) 96.dp else 72.dp
            val vehicleIconHeight = if (isTablet) 96.dp else 72.dp
            val finalizeButtonHeight = if (isTablet) 80.dp else 64.dp

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
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(sectionGap),
                    ) {
                        PhotoSection(
                            buttonText = "Добавить фото документов",
                            isTablet = isTablet,
                            buttonHeight = photoButtonHeight,
                            onTakePhoto = takeDocumentPhoto,
                            photoPaths = state.documentPhotoPaths,
                            onRemovePhoto = vm::removeDocumentPhoto,
                            onPreviewPhoto = { previewPath = it },
                        )

                        PhotoSection(
                            buttonText = "Добавить фото груза и госномера",
                            isTablet = isTablet,
                            buttonHeight = photoButtonHeight,
                            onTakePhoto = takeCargoPhoto,
                            photoPaths = state.cargoPhotoPaths,
                            onRemovePhoto = vm::removeCargoPhoto,
                            onPreviewPhoto = { previewPath = it },
                        )

                        VehicleTypeChips(
                            selectedCode = state.vehicleTypeCode,
                            onSelected = { vm.selectVehicle(it.code) },
                            maxItemsInRow = 2,
                            iconHeight = vehicleIconHeight,
                            showSubtitle = false,
                        )

                        MaterialsField(
                            value = state.materials,
                            onValueChange = vm::setMaterials,
                        )

                        ModalTextField(
                            value = state.commentText,
                            onValueChange = vm::setComment,
                            label = "Комментарий",
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = sectionGap),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Button(
                            onClick = vm::finalizeStage1,
                            enabled = !state.isSaving && !state.finalized,
                            modifier = Modifier.height(finalizeButtonHeight),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить 1 Этап",
                                style = if (isTablet)
                                    MaterialTheme.typography.headlineSmall
                                else
                                    MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
        }

        previewPath?.let { path ->
            PhotoPreviewDialog(
                filePath = path,
                onDismiss = { previewPath = null },
            )
        }
    }
}

@Composable
private fun PhotoSection(
    buttonText: String,
    isTablet: Boolean,
    buttonHeight: Dp,
    onTakePhoto: () -> Unit,
    photoPaths: List<String>,
    onRemovePhoto: (String) -> Unit,
    onPreviewPhoto: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onTakePhoto,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = buttonText,
                style = if (isTablet)
                    MaterialTheme.typography.headlineSmall
                else
                    MaterialTheme.typography.titleLarge,
            )
        }

        if (photoPaths.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(photoPaths, key = { it }) { path ->
                    PhotoThumb(
                        filePath = path,
                        onRemove = { onRemovePhoto(path) },
                        onClick = { onPreviewPhoto(path) },
                    )
                }
            }
        }
    }
}
