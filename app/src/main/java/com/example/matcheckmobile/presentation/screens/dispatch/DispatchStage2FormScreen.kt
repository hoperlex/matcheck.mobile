package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.EditableMaterialsInlineList
import com.example.matcheckmobile.presentation.components.FinalizeConfirmDialog
import com.example.matcheckmobile.presentation.components.FinalizeSuccessOverlay
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.MaterialEditDialog
import com.example.matcheckmobile.presentation.components.MaterialsTableHeader
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.RemotePhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.RemotePhotoRef
import com.example.matcheckmobile.presentation.components.Stage1PhotosSection
import com.example.matcheckmobile.presentation.components.rememberDocumentScanner
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.util.formatLocalTime
import com.example.matcheckmobile.presentation.viewmodel.DispatchStage2FormViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.delay

private val TabletBreakpoint = 600.dp

/** Зеркало [com.example.matcheckmobile.presentation.screens.receipt.Stage2FormScreen] для отгрузки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchStage2FormScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val vm: DispatchStage2FormViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Справочник единиц измерения из Room (см. Stage2FormScreen).
    val availableUnits by container.database.remoteUnitDao().observeActive()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unitCodes = remember(availableUnits) { availableUnits.map { it.code } }
    val snackbar = remember { SnackbarHostState() }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var addMaterialOpen by remember { mutableStateOf(false) }
    var confirmFinalizeVisible by remember { mutableStateOf(false) }
    var successVisible by remember { mutableStateOf(false) }
    // См. Stage2FormScreen: preview Stage 1 живёт отдельно от capture-flow
    // 2-го Этапа, чтобы не пересекаться с локальным previewPath/previewDelete.
    var stage1PreviewPhotos by remember { mutableStateOf<List<RemotePhotoRef>>(emptyList()) }
    var stage1PreviewIndex by remember { mutableStateOf(0) }
    var stage1PreviewVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val tapOutsideSource = remember { MutableInteractionSource() }

    LaunchedEffect(state.finalized) {
        if (state.finalized) {
            successVisible = true
            delay(900L)
            onFinalized()
        }
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
                title = { Text("Подтверждение отгрузки") },
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
                .padding(padding)
                .clickable(
                    interactionSource = tapOutsideSource,
                    indication = null,
                    onClick = { focusManager.clearFocus() },
                ),
        ) {
            val isTablet = maxWidth >= TabletBreakpoint
            val contentMaxWidth: Dp = if (isTablet) 900.dp else maxWidth
            val outerPadding = if (isTablet) 24.dp else 16.dp
            val sectionGap = if (isTablet) 20.dp else 14.dp
            val photoButtonHeight = if (isTablet) 128.dp else 116.dp
            val finalizeButtonHeight = if (isTablet) 80.dp else 64.dp

            val inputTextStyle = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            val inputLabelStyle = if (isTablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
            val photoButtonTextStyle = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = outerPadding, end = outerPadding, top = outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().imePadding(),
                        verticalArrangement = Arrangement.spacedBy(sectionGap),
                    ) {
                        Stage1PhotosSection(
                            documentPhotos = state.stage1DocumentPhotos,
                            vehiclePhotos = state.stage1VehiclePhotos,
                            stage1TimeLabel = formatLocalTime(state.shippedAtMs),
                            onPhotoClick = { clicked, columnPhotos ->
                                stage1PreviewPhotos = columnPhotos.map {
                                    RemotePhotoRef(
                                        photoId = it.photoId,
                                        localBlobPath = it.localBlobPath,
                                    )
                                }
                                stage1PreviewIndex = columnPhotos.indexOf(clicked)
                                    .coerceAtLeast(0)
                                stage1PreviewVisible = true
                            },
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
                                onPreviewPhoto = { path, onDelete ->
                                    previewPath = path
                                    previewDelete = onDelete
                                },
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
                                onPreviewPhoto = { path, onDelete ->
                                    previewPath = path
                                    previewDelete = onDelete
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        OutlinedTextField(
                            value = state.vehiclePlate.orEmpty(),
                            onValueChange = {},
                            enabled = false,
                            readOnly = true,
                            singleLine = true,
                            textStyle = inputTextStyle,
                            label = { Text("Госномер", style = inputLabelStyle) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )

                        MaterialsTableHeader(
                            headerStyle = if (isTablet) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.labelLarge,
                            onAddClick = { addMaterialOpen = true },
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EditableMaterialsInlineList(
                                value = state.materials,
                                editedIndexes = state.editedIndexes,
                                onEdit = vm::updateMaterial,
                                onDelete = vm::deleteMaterial,
                                showHeader = false,
                                originalMaterials = state.originalMaterials,
                                editingShowUnitField = false,
                                availableUnits = unitCodes,
                            )
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            state.stage1Comment?.takeIf { it.isNotBlank() }?.let { stage1Text ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = "1 Этап:",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(
                                        text = stage1Text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = state.commentText,
                                onValueChange = vm::setComment,
                                label = { Text("Комментарий 2 Этап", style = inputLabelStyle) },
                                singleLine = true,
                                textStyle = inputTextStyle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = outerPadding),
                    ) {
                        Button(
                            onClick = { confirmFinalizeVisible = true },
                            enabled = state.loaded && !state.isSaving && !state.finalized,
                            modifier = Modifier.height(finalizeButtonHeight),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить 2 Этап",
                                style = if (isTablet) MaterialTheme.typography.headlineMedium
                                    else MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
        }

        previewPath?.let { path ->
            PhotoPreviewDialog(
                filePath = path,
                onDismiss = {
                    previewPath = null
                    previewDelete = null
                },
                onDelete = previewDelete,
            )
        }

        if (addMaterialOpen) {
            MaterialEditDialog(
                initial = MaterialDraft(name = "", qty = "", unit = "шт"),
                title = "Добавить материал",
                onDismiss = { addMaterialOpen = false },
                onSave = { draft ->
                    vm.addMaterial(draft)
                    addMaterialOpen = false
                },
                availableUnits = unitCodes,
            )
        }

        if (confirmFinalizeVisible) {
            FinalizeConfirmDialog(
                title = "Завершить 2 Этап?",
                onConfirm = {
                    confirmFinalizeVisible = false
                    vm.finalizeStage2()
                },
                onDismiss = { confirmFinalizeVisible = false },
            )
        }

        if (successVisible) {
            FinalizeSuccessOverlay()
        }

        if (stage1PreviewVisible && stage1PreviewPhotos.isNotEmpty()) {
            RemotePhotoPreviewDialog(
                photos = stage1PreviewPhotos,
                initialIndex = stage1PreviewIndex,
                onDismiss = {
                    stage1PreviewVisible = false
                    stage1PreviewPhotos = emptyList()
                    stage1PreviewIndex = 0
                },
            )
        }
    }
}
