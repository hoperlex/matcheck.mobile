package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.VehicleTypeChips
import com.example.matcheckmobile.presentation.components.rememberDocumentScanner
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.viewmodel.Stage2FormViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val TabletBreakpoint = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage2FormScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val vm: Stage2FormViewModel = matcheckViewModel()
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

    val takeDocumentPhoto = rememberDocumentScanner(
        photoStorage = container.photoStorage,
        onPageCaptured = vm::onDocumentPhotoTaken,
    )
    val takeVehiclePhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::onVehiclePhotoTaken,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подтверждение приёмки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            val photoButtonHeight = if (isTablet) 128.dp else 104.dp
            val vehicleIconHeight = if (isTablet) 96.dp else 72.dp
            val photoButtonTextStyle = if (isTablet)
                MaterialTheme.typography.headlineSmall
            else
                MaterialTheme.typography.titleLarge

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(sectionGap),
                ) {
                    DeliveryInfoCard(
                        plate = state.vehiclePlate,
                        updDisplay = state.updDisplay,
                        isTablet = isTablet,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(sectionGap),
                    ) {
                        PhotoCaptureSection(
                            buttonText = "Фото документов",
                            buttonTextStyle = photoButtonTextStyle,
                            isTablet = isTablet,
                            buttonHeight = photoButtonHeight,
                            onTakePhoto = takeDocumentPhoto,
                            photoPaths = state.documentPhotoPaths,
                            onRemovePhoto = vm::removeDocumentPhoto,
                            onPreviewPhoto = { previewPath = it },
                            modifier = Modifier.weight(1f),
                        )

                        PhotoCaptureSection(
                            buttonText = "Фото машины, госномера",
                            buttonTextStyle = photoButtonTextStyle,
                            isTablet = isTablet,
                            buttonHeight = photoButtonHeight,
                            onTakePhoto = takeVehiclePhoto,
                            photoPaths = state.vehiclePhotoPaths,
                            onRemovePhoto = vm::removeVehiclePhoto,
                            onPreviewPhoto = { previewPath = it },
                            modifier = Modifier.weight(1f),
                        )
                    }

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

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Button(
                            onClick = vm::finalizeStage2,
                            enabled = state.loaded && !state.isSaving && !state.finalized,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить 2 Этап",
                                style = MaterialTheme.typography.titleMedium,
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

/**
 * Информационный блок сверху Stage 2 — read-only сводка по приёмке: госномер
 * и номер УПД. Нужен инспектору/МОЛ, чтобы сверить машину при подтверждении.
 */
@Composable
private fun DeliveryInfoCard(
    plate: String?,
    updDisplay: String?,
    isTablet: Boolean,
) {
    val plateValue = plate?.takeIf { it.isNotBlank() } ?: "—"
    val updValue = updDisplay?.takeIf { it.isNotBlank() } ?: "—"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 6.dp else 4.dp),
        ) {
            InfoRow(label = "Госномер", value = plateValue, isTablet = isTablet)
            InfoRow(label = "УПД", value = updValue, isTablet = isTablet)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, isTablet: Boolean) {
    Text(
        text = "$label: $value",
        style = if (isTablet)
            MaterialTheme.typography.titleLarge
        else
            MaterialTheme.typography.titleMedium,
    )
}
