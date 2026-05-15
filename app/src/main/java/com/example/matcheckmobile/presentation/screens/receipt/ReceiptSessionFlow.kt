package com.example.matcheckmobile.presentation.screens.receipt

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.presentation.components.VehicleTypeChips
import com.example.matcheckmobile.presentation.viewmodel.ReceiptSessionViewModel
import com.example.matcheckmobile.presentation.viewmodel.ReceiptStep
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val FormMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSessionFlow(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm: ReceiptSessionViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finalized) {
        if (state.finalized) {
            vm.acknowledgeFinalized()
            onSaved()
        }
    }

    when (state.step) {
        ReceiptStep.MAIN -> MainStep(vm = vm, onBack = onBack)
        ReceiptStep.ITEM_FORM -> ItemFormStep(vm = vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainStep(vm: ReceiptSessionViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val sessionPhotos by vm.sessionPhotos.collectAsStateWithLifecycle()
    val resolvedSite by vm.resolvedSite.collectAsStateWithLifecycle()
    val resolvedSupplier by vm.resolvedSupplier.collectAsStateWithLifecycle()
    val resolvedContractor by vm.resolvedContractor.collectAsStateWithLifecycle()
    val resolvedDocument by vm.resolvedDocument.collectAsStateWithLifecycle()

    var showSitePicker by remember { mutableStateOf(false) }
    var showSupplierPicker by remember { mutableStateOf(false) }
    var showContractorPicker by remember { mutableStateOf(false) }
    var showDocumentPicker by remember { mutableStateOf(false) }
    var showManualDocDialog by remember { mutableStateOf(false) }
    var showVehicleDialog by remember { mutableStateOf(false) }
    var showVolumeDialog by remember { mutableStateOf(false) }
    var showMassDialog by remember { mutableStateOf(false) }
    var showCommentDialog by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val photoStorage = remember {
        (context.applicationContext as MatcheckApplication).container.photoStorage
    }
    var pendingPath by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPath
        if (success && path != null) vm.addSessionPhoto(path)
        pendingPath = null
    }

    val photosCount = sessionPhotos.size + state.sessionPhotoPaths.size
    val itemsCount = items.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая приёмка") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.sessionId != null) {
                        IconButton(onClick = { showCancelConfirm = true }) {
                            Icon(Icons.Default.Close, contentDescription = "Отменить черновик")
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.headerError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setConfirmedByMol(!state.confirmedByMol) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.confirmedByMol,
                            onCheckedChange = vm::setConfirmedByMol,
                        )
                        Text(
                            "Подтверждено МОЛ",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = vm::saveLocally,
                            enabled = !state.isFinalizing,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                        ) {
                            Text(
                                if (state.isFinalizing) "..." else "Сохранить",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Button(
                            onClick = vm::completeSession,
                            enabled = !state.isFinalizing,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                        ) {
                            Text(
                                if (state.isFinalizing) "..." else "Закончить приёмку",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        CenteredColumn(padding = padding) {
            PickerRow(
                label = "Документ (УПД)",
                value = state.sourceDocumentText.ifBlank { "Не указан" },
                onClick = { showDocumentPicker = true },
            )
            PickerRow(
                label = "Объект",
                value = resolvedSite?.name ?: "Не выбран",
                onClick = { showSitePicker = true },
            )
            PickerRow(
                label = "Подрядчик",
                value = resolvedContractor?.name ?: "Не указан",
                onClick = { showContractorPicker = true },
            )
            PickerRow(
                label = "Поставщик",
                value = resolvedSupplier?.name ?: "Не указан",
                onClick = { showSupplierPicker = true },
            )
            PickerRow(
                label = "Госномер машины",
                value = state.vehicleNumber.ifBlank { "Не указан" },
                onClick = { showVehicleDialog = true },
            )
            VehicleTypeChips(
                selectedCode = state.vehicleTypeCode,
                onSelected = vm::selectVehicleType,
            )
            PickerRow(
                label = "Объём, м³",
                value = state.volumeText.ifBlank { "Не указан" },
                onClick = { showVolumeDialog = true },
            )
            PickerRow(
                label = "Масса, кг",
                value = state.massText.ifBlank { "Не указан" },
                onClick = { showMassDialog = true },
            )
            PickerRow(
                label = "Комментарий",
                value = state.comment.ifBlank { "Не указан" },
                onClick = { showCommentDialog = true },
            )
            OutlinedButton(
                onClick = {
                    val file = photoStorage.createTempFile()
                    val uri: Uri = photoStorage.toContentUri(file)
                    pendingPath = file.absolutePath
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("  Добавить фото", style = MaterialTheme.typography.titleMedium)
            }
            if (photosCount > 0) {
                Text(
                    "Прикреплено фото: $photosCount",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showSitePicker) {
        SitePickerSheet(
            sites = sites,
            selected = state.siteLocalId,
            onPick = {
                vm.setSite(it)
                showSitePicker = false
            },
            onDismiss = { showSitePicker = false },
        )
    }
    if (showSupplierPicker) {
        CounterpartyPickerSheet(
            title = "Выберите поставщика",
            counterparties = suppliers,
            selected = state.supplierLocalId,
            onPick = {
                vm.setSupplier(it)
                showSupplierPicker = false
            },
            onDismiss = { showSupplierPicker = false },
        )
    }
    if (showContractorPicker) {
        CounterpartyPickerSheet(
            title = "Выберите подрядчика",
            counterparties = contractors,
            selected = state.contractorLocalId,
            onPick = {
                vm.setContractor(it)
                showContractorPicker = false
            },
            onDismiss = { showContractorPicker = false },
        )
    }
    if (showDocumentPicker) {
        DocumentPickerSheet(
            onNotSpecified = {
                vm.setSourceDocumentText("")
                showDocumentPicker = false
            },
            onManualEntry = {
                showDocumentPicker = false
                showManualDocDialog = true
            },
            onDismiss = { showDocumentPicker = false },
        )
    }
    if (showManualDocDialog) {
        ManualEntryDialog(
            title = "Документ (УПД)",
            fieldLabel = "Номер документа",
            initial = state.sourceDocumentText,
            onConfirm = {
                vm.setSourceDocumentText(it)
                showManualDocDialog = false
            },
            onDismiss = { showManualDocDialog = false },
        )
    }
    if (showVehicleDialog) {
        ManualEntryDialog(
            title = "Госномер машины",
            fieldLabel = "Госномер",
            initial = state.vehicleNumber,
            onConfirm = {
                vm.setVehicleNumber(it)
                showVehicleDialog = false
            },
            onDismiss = { showVehicleDialog = false },
        )
    }
    if (showVolumeDialog) {
        ManualEntryDialog(
            title = "Объём груза",
            fieldLabel = "Объём, м³",
            initial = state.volumeText,
            onConfirm = {
                vm.setVolumeText(it)
                showVolumeDialog = false
            },
            onDismiss = { showVolumeDialog = false },
            keyboardType = KeyboardType.Number,
        )
    }
    if (showMassDialog) {
        ManualEntryDialog(
            title = "Масса груза",
            fieldLabel = "Масса, кг",
            initial = state.massText,
            onConfirm = {
                vm.setMassText(it)
                showMassDialog = false
            },
            onDismiss = { showMassDialog = false },
            keyboardType = KeyboardType.Number,
        )
    }
    if (showCommentDialog) {
        ManualEntryDialog(
            title = "Комментарий",
            fieldLabel = "Комментарий",
            initial = state.comment,
            onConfirm = {
                vm.setComment(it)
                showCommentDialog = false
            },
            onDismiss = { showCommentDialog = false },
            singleLine = false,
            minLines = 3,
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Отменить черновик?") },
            text = { Text("Все позиции и фото в этом черновике будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    vm.cancelDraft()
                }) { Text("Отменить черновик") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Назад") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemFormStep(vm: ReceiptSessionViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.itemForm
    val context = LocalContext.current
    val photoStorage = remember {
        (context.applicationContext as MatcheckApplication).container.photoStorage
    }
    var pendingPath by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPath
        if (success && path != null) vm.addItemPhoto(path)
        pendingPath = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Позиция") },
                navigationIcon = {
                    IconButton(onClick = vm::closeItemForm) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar {
                Button(
                    onClick = vm::saveItem,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                ) {
                    Text("Сохранить позицию", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { padding ->
        CenteredColumn(padding = padding) {
            OutlinedTextField(
                value = form.materialName,
                onValueChange = vm::setItemMaterialName,
                label = { Text("Наименование материала") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.quantityText,
                    onValueChange = vm::setItemQuantity,
                    label = { Text("Количество") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.unit,
                    onValueChange = vm::setItemUnit,
                    label = { Text("Ед.") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = form.comment,
                onValueChange = vm::setItemComment,
                label = { Text("Комментарий") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedButton(
                onClick = {
                    val file = photoStorage.createTempFile()
                    val uri: Uri = photoStorage.toContentUri(file)
                    pendingPath = file.absolutePath
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("  Добавить фото", style = MaterialTheme.typography.titleMedium)
            }
            if (form.photoPaths.isNotEmpty()) {
                Text("Прикреплено: ${form.photoPaths.size}", style = MaterialTheme.typography.bodyMedium)
            }
            form.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitePickerSheet(
    sites: List<SiteEntity>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                "Выберите объект",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            sites.forEach { site ->
                ListItem(
                    headlineContent = { Text(site.name) },
                    supportingContent = site.address?.let { addr -> { Text(addr) } },
                    trailingContent = if (site.localId == selected) {
                        { Text("✓", style = MaterialTheme.typography.titleMedium) }
                    } else null,
                    modifier = Modifier.clickable { onPick(site.localId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CounterpartyPickerSheet(
    title: String,
    counterparties: List<CounterpartyEntity>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Не указан") },
                modifier = Modifier.clickable { onPick(null) },
            )
            counterparties.forEach { cp ->
                ListItem(
                    headlineContent = { Text(cp.name) },
                    supportingContent = { Text("ИНН ${cp.inn}") },
                    trailingContent = if (cp.localId == selected) {
                        { Text("✓", style = MaterialTheme.typography.titleMedium) }
                    } else null,
                    modifier = Modifier.clickable { onPick(cp.localId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPickerSheet(
    onNotSpecified: () -> Unit,
    onManualEntry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                "Документ (УПД)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Не указан") },
                modifier = Modifier.clickable { onNotSpecified() },
            )
            ListItem(
                headlineContent = { Text("Ввести вручную") },
                modifier = Modifier.clickable { onManualEntry() },
            )
        }
    }
}

@Composable
private fun ManualEntryDialog(
    title: String,
    fieldLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(fieldLabel) },
                singleLine = singleLine,
                minLines = minLines,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("ОК") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CenteredColumn(
    padding: PaddingValues,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val isTablet = maxWidth >= 600.dp
        val outer = if (isTablet) 32.dp else 16.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(outer),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = FormMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun BottomActionBar(content: @Composable () -> Unit) {
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
            Box(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth()) {
                content()
            }
        }
    }
}

private fun formatQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
