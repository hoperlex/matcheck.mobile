package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.AssetsCheckbox
import com.example.matcheckmobile.presentation.components.FinalizeConfirmDialog
import com.example.matcheckmobile.presentation.components.MaterialsField
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.TransitCheckbox
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo
import com.example.matcheckmobile.presentation.components.VehicleTypeChips
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.viewmodel.DispatchStage1FormViewModel
import com.example.matcheckmobile.presentation.viewmodel.SHIPMENT_PURPOSE_OPTIONS
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel
import kotlinx.coroutines.launch

private val TabletBreakpoint = 600.dp

/** Зеркало [com.example.matcheckmobile.presentation.screens.receipt.Stage1FormScreen] для отгрузки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchStage1FormScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val vm: DispatchStage1FormViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmFinalizeVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val tapOutsideSource = remember { MutableInteractionSource() }

    // Финализация прошла — уходим с формы немедленно. Зелёную галочку рисует
    // FinalizeSuccessHost поверх Activity: раньше выход ждал её анимацию, и
    // сбой этого ожидания запирал инспектора на уже сохранённой форме.
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
        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        requestLocation = false, // документам локация не нужна — штамп не наносится
    )
    val takeCargoPhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::onCargoPhotoTaken,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая отгрузка", style = MaterialTheme.typography.headlineSmall) },
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
            val vehicleIconHeight = if (isTablet) 96.dp else 72.dp
            val finalizeButtonHeight = if (isTablet) 80.dp else 64.dp

            val inputTextStyle = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            val inputLabelStyle = if (isTablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
            val photoButtonTextStyle = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
            val materialsButtonTextStyle = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            val vehicleLabelStyle = if (isTablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
            val vehicleChipTitleStyle = if (isTablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium

            val density = LocalDensity.current
            val imeBottomPx = WindowInsets.ime.getBottom(density)
            val isImeVisible = imeBottomPx > 0
            val scrollBottomReserve = if (isImeVisible) 0.dp
                else finalizeButtonHeight + sectionGap + outerPadding

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = outerPadding, end = outerPadding, top = outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = scrollBottomReserve),
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
                                onPreviewPhoto = { path, onDelete ->
                                    previewPath = path
                                    previewDelete = onDelete
                                },
                                modifier = Modifier.weight(1f),
                            )
                            PhotoCaptureSection(
                                buttonText = "Фото груза, госномера",
                                buttonTextStyle = photoButtonTextStyle,
                                isTablet = isTablet,
                                buttonHeight = photoButtonHeight,
                                onTakePhoto = takeCargoPhoto,
                                photoPaths = state.cargoPhotoPaths,
                                onRemovePhoto = vm::removeCargoPhoto,
                                onPreviewPhoto = { path, onDelete ->
                                    previewPath = path
                                    previewDelete = onDelete
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        OutlinedTextField(
                            value = state.licensePlate,
                            onValueChange = { vm.setLicensePlate(it.uppercase()) },
                            label = { Text("Введите Госномер", style = inputLabelStyle) },
                            isError = state.showPlateError,
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (state.updId == null) {
                            OutlinedTextField(
                                value = state.manualUpdText,
                                onValueChange = vm::setManualUpd,
                                label = { Text("Введите УПД", style = inputLabelStyle) },
                                singleLine = true,
                                textStyle = inputTextStyle,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // Dropdown «Тип отгрузки» — только для empty-draft.
                            // 4 фиксированных варианта (см. SHIPMENT_PURPOSE_OPTIONS).
                            // Значение сохраняется в shipment_stage1_draft и на
                            // finalize попадает префиксом «Тип: …» в comment.
                            var purposeExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = purposeExpanded,
                                onExpandedChange = { purposeExpanded = !purposeExpanded },
                            ) {
                                OutlinedTextField(
                                    value = state.shipmentPurpose.orEmpty(),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Тип отгрузки", style = inputLabelStyle) },
                                    textStyle = inputTextStyle,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = purposeExpanded)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                )
                                ExposedDropdownMenu(
                                    expanded = purposeExpanded,
                                    onDismissRequest = { purposeExpanded = false },
                                ) {
                                    SHIPMENT_PURPOSE_OPTIONS.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option, style = inputTextStyle) },
                                            onClick = {
                                                vm.setShipmentPurpose(option)
                                                purposeExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Блок «Тип транспорта» показываем только для отгрузки из
                        // подгруженной УПД. В ручной отгрузке («Создать отгрузку»,
                        // updId == null) он не нужен — по запросу скрыт.
                        if (state.updId != null) {
                            VehicleTypeChips(
                                selectedCode = state.vehicleTypeCode,
                                onSelected = {
                                    focusManager.clearFocus()
                                    vm.selectVehicle(it.code)
                                },
                                maxItemsInRow = 2,
                                iconHeight = vehicleIconHeight,
                                showSubtitle = false,
                                loadInfo = state.loadInfo ?: VehicleLoadInfo(null, null),
                                labelStyle = vehicleLabelStyle,
                                chipTitleStyle = vehicleChipTitleStyle,
                                trailingHeader = {
                                    // Picked-UPD сценарий Выезда: чекбоксы ОС +
                                    // Транзит справа от «Тип транспорта». ОС
                                    // стоит ПЕРЕД Транзитом (для подгруженных
                                    // УПД). Empty-draft — строка ниже комментария.
                                    androidx.compose.foundation.layout.Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        AssetsCheckbox(
                                            checked = state.isAssets,
                                            onCheckedChange = {
                                                focusManager.clearFocus()
                                                vm.setIsAssets(it)
                                            },
                                        )
                                        TransitCheckbox(
                                            checked = state.inTransit,
                                            onCheckedChange = {
                                                focusManager.clearFocus()
                                                vm.setInTransit(it)
                                            },
                                        )
                                    }
                                },
                            )
                        }

                        // Материалы показываем только в picked-UPD сценарии Выезда.
                        // В empty-draft («Создать отгрузку» без УПД) материалы не могут
                        // появиться автоматически, а редактировать их на 1 Этапе нельзя —
                        // блок «Материалы (0)» только смущает инспектора.
                        if (state.updId != null) {
                            MaterialsField(
                                value = state.materials,
                                onValueChange = vm::setMaterials,
                                readOnly = true,
                                buttonTextStyle = materialsButtonTextStyle,
                                buttonMinHeight = if (isTablet) 72.dp else 64.dp,
                            )
                        }

                        OutlinedTextField(
                            value = state.commentText,
                            onValueChange = vm::setComment,
                            label = { Text("Комментарий", style = inputLabelStyle) },
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Empty-draft Выезда («Создать отгрузку»): чекбоксы
                        // Транзит и ОС в одной строке ниже комментария.
                        // На picked-UPD сценарии оба чекбокса находятся справа
                        // от «Тип транспорта» (см. VehicleTypeChips trailingHeader).
                        if (state.updId == null) {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TransitCheckbox(
                                    checked = state.inTransit,
                                    onCheckedChange = {
                                        focusManager.clearFocus()
                                        vm.setInTransit(it)
                                    },
                                )
                                AssetsCheckbox(
                                    checked = state.isAssets,
                                    onCheckedChange = {
                                        focusManager.clearFocus()
                                        vm.setIsAssets(it)
                                    },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = outerPadding),
                    ) {
                        Button(
                            onClick = { confirmFinalizeVisible = true },
                            enabled = !state.isSaving && !state.finalized,
                            modifier = Modifier.height(finalizeButtonHeight),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить 1 Этап",
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

        if (confirmFinalizeVisible) {
            FinalizeConfirmDialog(
                title = "Завершить 1 Этап?",
                onConfirm = {
                    confirmFinalizeVisible = false
                    vm.finalizeStage1()
                },
                onDismiss = { confirmFinalizeVisible = false },
                // Double-tap guard, симметрично Stage1FormScreen (Приёмка).
                confirmEnabled = !state.isSaving,
            )
        }
    }
}
