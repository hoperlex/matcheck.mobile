package com.example.matcheckmobile.presentation.screens.receipt

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.presentation.components.AssetsCheckbox
import com.example.matcheckmobile.presentation.components.EditableMaterialsInlineList
import com.example.matcheckmobile.presentation.components.FinalizeConfirmDialog
import com.example.matcheckmobile.presentation.components.FinalizeSuccessOverlay
import com.example.matcheckmobile.presentation.components.MaterialDraft
import com.example.matcheckmobile.presentation.components.MaterialEditDialog
import com.example.matcheckmobile.presentation.components.MaterialsTableHeader
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.rememberDocumentScanner
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import com.example.matcheckmobile.presentation.viewmodel.ManualEntryFormViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

private val TabletBreakpoint = 600.dp

/**
 * Экран «Ручной внос» — приёмка без УПД и автотранспорта.
 *
 * Структурно — упрощённая копия [Stage1FormScreen] (см. там подробные
 * комментарии по layout/ime/imePadding/скроллу). Отличия:
 *  - нет ввода госномера (это не автотранспорт);
 *  - нет блока «Тип транспорта»;
 *  - нет чекбокса «Транзит»;
 *  - кнопка просто «Завершить» (без «1 Этап» в подписи);
 *  - на финализе VM создаёт приёмку сразу со статусом confirmed_mol.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryFormScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val vm: ManualEntryFormViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Справочник единиц измерения из Room (приходит /sync'ом). Используется
    // дропдауном «Ед.» в MaterialEditDialog. Пустой список в начале →
    // fallback text-input в самом диалоге (см. MaterialEditDialog).
    val availableUnits by container.database.remoteUnitDao().observeActive()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unitCodes = remember(availableUnits) { availableUnits.map { it.code } }
    val snackbar = remember { SnackbarHostState() }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var addMaterialOpen by remember { mutableStateOf(false) }
    var confirmFinalizeVisible by remember { mutableStateOf(false) }
    var successVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val tapOutsideSource = remember { MutableInteractionSource() }

    // Системный back проходит через ту же onLeave: пустой черновик — удалить,
    // содержательный — дозаписать (страховка от несработавшего автосейва).
    BackHandler { vm.onLeave(onBack) }

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
        onPageCaptured = vm::addDocumentPhoto,
    )
    val takeCargoPhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::addCargoPhoto,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Новая приёмка",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.onLeave(onBack) }, modifier = Modifier.size(72.dp)) {
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
            val finalizeButtonHeight = if (isTablet) 64.dp else 52.dp

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
                MaterialTheme.typography.titleMedium

            val density = LocalDensity.current
            val imeBottomPx = WindowInsets.ime.getBottom(density)
            val isImeVisible = imeBottomPx > 0
            val scrollBottomReserve = if (isImeVisible) {
                0.dp
            } else {
                finalizeButtonHeight + sectionGap + outerPadding
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = outerPadding,
                        end = outerPadding,
                        top = outerPadding,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize(),
                ) {
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

                            // Для «Ручного внеса» подпись без «, госномера»:
                            // автотранспорта нет, значит и госномер не снимается.
                            PhotoCaptureSection(
                                buttonText = "Фото груза",
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
                            value = state.manualUpdText,
                            onValueChange = vm::setManualUpd,
                            label = { Text("Введите УПД", style = inputLabelStyle) },
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Inline-таблица материалов: шапка со встроенной кнопкой
                        // «+ Добавить материал» + строки с редактированием по тапу
                        // / свайп-удалением. Тот же паттерн, что в Stage2FormScreen,
                        // но без originalMaterials (на ручном внесе нет серверного
                        // снимка — список редактируется с нуля).
                        MaterialsTableHeader(
                            headerStyle = if (isTablet)
                                MaterialTheme.typography.titleMedium
                            else
                                MaterialTheme.typography.labelLarge,
                            onAddClick = { addMaterialOpen = true },
                        )

                        EditableMaterialsInlineList(
                            value = state.materials,
                            // editedIndexes здесь не используется — все строки
                            // новые, подсвечивать нечего; передаём пустой Set.
                            editedIndexes = emptySet(),
                            onEdit = vm::updateMaterial,
                            onDelete = vm::deleteMaterial,
                            showHeader = false,
                            // На правке существующей строки поле «Ед.» оставляем
                            // видимым — инспектор может ошибиться при добавлении
                            // и потом захочет поправить. На 2 Этапе оно скрыто,
                            // потому что там единицы приходят с сервера; здесь
                            // источник единиц = ввод инспектора.
                            editingShowUnitField = true,
                            availableUnits = unitCodes,
                        )

                        OutlinedTextField(
                            value = state.commentText,
                            onValueChange = vm::setComment,
                            label = { Text("Комментарий", style = inputLabelStyle) },
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Чекбокс «ОС» (основные средства) слева, ниже Комментария.
                        // Транзит в ручном внесе отсутствует (это не автотранспорт).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AssetsCheckbox(
                                checked = state.isAssets,
                                onCheckedChange = {
                                    focusManager.clearFocus()
                                    vm.setIsAssets(it)
                                },
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
                            enabled = !state.isSaving && !state.finalized,
                            modifier = Modifier.height(finalizeButtonHeight),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Завершить",
                                style = if (isTablet)
                                    MaterialTheme.typography.titleLarge
                                else
                                    MaterialTheme.typography.titleMedium,
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
                title = "Завершить ручной внос?",
                onConfirm = {
                    confirmFinalizeVisible = false
                    vm.finalize()
                },
                onDismiss = { confirmFinalizeVisible = false },
            )
        }

        if (successVisible) {
            FinalizeSuccessOverlay()
        }
    }
}
