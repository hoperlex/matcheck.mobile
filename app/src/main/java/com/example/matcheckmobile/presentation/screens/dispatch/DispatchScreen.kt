package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.viewmodel.DispatchSessionViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val FormMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm: DispatchSessionViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val contractors by vm.contractors.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val resolvedSite by vm.resolvedSite.collectAsStateWithLifecycle()
    val resolvedContractor by vm.resolvedContractor.collectAsStateWithLifecycle()
    val resolvedDocument by vm.resolvedDocument.collectAsStateWithLifecycle()

    var showSitePicker by remember { mutableStateOf(false) }
    var showContractorPicker by remember { mutableStateOf(false) }
    var showDocumentPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val photoStorage = remember {
        (context.applicationContext as MatcheckApplication).container.photoStorage
    }
    val takePhoto = rememberPhotoCapture(
        photoStorage = photoStorage,
        onPhotoTaken = vm::addPhotoPath,
        onError = { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        },
    )

    LaunchedEffect(state.savedSessionId) {
        if (state.savedSessionId != null) {
            vm.resetSaved()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выезд материала") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = vm::submit,
                        enabled = !state.isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    ) {
                        Text(
                            if (state.isSubmitting) "Сохранение..." else "Сохранить локально",
                            style = MaterialTheme.typography.titleLarge,
                        )
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
            val isTablet = maxWidth >= 600.dp
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
                    OutlinedTextField(
                        value = state.vehicleNumber,
                        onValueChange = vm::setVehicleNumber,
                        label = { Text("Госномер машины") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PickerRow(
                        label = "Документ (УПД)",
                        value = resolvedDocument?.let { "УПД ${it.docNumber ?: "—"}" } ?: "Не привязан",
                        onClick = { showDocumentPicker = true },
                    )
                    OutlinedTextField(
                        value = state.comment,
                        onValueChange = vm::setComment,
                        label = { Text("Комментарий") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedButton(
                        onClick = takePhoto,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
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
                            LazyColumn {
                                items(state.pendingPhotoPaths) { path ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = path.substringAfterLast('/'),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(8.dp),
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
    if (showContractorPicker) {
        ContractorPickerSheet(
            contractors = contractors,
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
            documents = documents,
            selected = state.sourceDocumentLocalId,
            onPick = {
                vm.setSourceDocument(it)
                showDocumentPicker = false
            },
            onDismiss = { showDocumentPicker = false },
        )
    }
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
private fun ContractorPickerSheet(
    contractors: List<CounterpartyEntity>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                "Выберите подрядчика",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Не указан") },
                modifier = Modifier.clickable { onPick(null) },
            )
            contractors.forEach { cp ->
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
    documents: List<SourceDocumentEntity>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                "Выберите УПД",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Без привязки") },
                modifier = Modifier.clickable { onPick(null) },
            )
            LazyColumn {
                items(documents, key = { it.localId }) { doc ->
                    ListItem(
                        headlineContent = { Text("УПД ${doc.docNumber ?: "—"}") },
                        supportingContent = doc.totalSum?.let { sum ->
                            { Text("%.2f ₽".format(sum)) }
                        },
                        trailingContent = if (doc.localId == selected) {
                            { Text("✓", style = MaterialTheme.typography.titleMedium) }
                        } else null,
                        modifier = Modifier.clickable { onPick(doc.localId) },
                    )
                }
            }
        }
    }
}
