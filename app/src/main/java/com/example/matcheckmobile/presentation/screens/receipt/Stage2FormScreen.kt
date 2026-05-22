package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.EditableMaterialsInlineList
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.MaterialEditDialog
import com.example.matcheckmobile.presentation.components.MaterialsTableHeader
import com.example.matcheckmobile.presentation.components.ModalTextField
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
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
    var addMaterialOpen by remember { mutableStateOf(false) }

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
            val photoButtonHeight = if (isTablet) 128.dp else 104.dp
            val finalizeButtonHeight = if (isTablet) 80.dp else 64.dp

            val inputTextStyle = if (isTablet)
                MaterialTheme.typography.headlineSmall
            else
                MaterialTheme.typography.titleLarge
            val inputLabelStyle = if (isTablet)
                MaterialTheme.typography.titleLarge
            else
                MaterialTheme.typography.titleMedium
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
                // Sticky-зоны: фото + Госномер закреплены сверху, Комментарий +
                // кнопка — снизу. Скроллится только средняя область со списком
                // материалов (weight=1f + verticalScroll).
                Column(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(sectionGap),
                ) {
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

                    // Госномер из приёмки — чистое отображение, без реакции
                    // на тап (нет фокуса, курсора, тулбара). enabled=false
                    // глушит обработку touch'ей, а disabled-цвета приведены
                    // к виду обычного поля, чтобы рамка/текст не тускнели.
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

                    // Шапка таблицы — sticky над скроллом, чтобы при пролистывании
                    // списка наверх «Название / Кол-во / Ед.» оставались видны.
                    // Кнопка «+ Добавить» встроена в шапку между «Название» и
                    // «Кол-во», поэтому отдельной кнопки под шапкой больше нет.
                    MaterialsTableHeader(
                        headerStyle = if (isTablet)
                            MaterialTheme.typography.titleMedium
                        else
                            MaterialTheme.typography.labelLarge,
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
                        )
                    }

                    ModalTextField(
                        value = state.commentText,
                        onValueChange = vm::setComment,
                        label = "Комментарий",
                        textStyle = inputTextStyle,
                        labelStyle = inputLabelStyle,
                    )

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Button(
                            onClick = vm::finalizeStage2,
                            enabled = state.loaded && !state.isSaving && !state.finalized,
                            modifier = Modifier.height(finalizeButtonHeight),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить 2 Этап",
                                style = if (isTablet)
                                    MaterialTheme.typography.headlineMedium
                                else
                                    MaterialTheme.typography.headlineSmall,
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

        if (addMaterialOpen) {
            MaterialEditDialog(
                initial = MaterialDraft(name = "", qty = "", unit = "шт"),
                title = "Добавить материал",
                onDismiss = { addMaterialOpen = false },
                onSave = { draft ->
                    vm.addMaterial(draft)
                    addMaterialOpen = false
                },
            )
        }
    }
}
