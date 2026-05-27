package com.example.matcheckmobile.presentation.screens.receipt

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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.matcheckmobile.presentation.components.rememberDocumentScanner
import com.example.matcheckmobile.presentation.components.rememberPhotoCapture
import kotlinx.coroutines.delay
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
    // Колбэк удаления для текущего превью — каждая PhotoCaptureSection
    // прокидывает сюда замыкание под своё фото. Кнопка «Удалить» внутри
    // PhotoPreviewDialog вызывает его после подтверждения.
    var previewDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var addMaterialOpen by remember { mutableStateOf(false) }
    var confirmFinalizeVisible by remember { mutableStateOf(false) }
    var successVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Источник для невидимого clickable на «пустые» зоны экрана — тап мимо
    // инпутов сворачивает клавиатуру через clearFocus, как на Stage1.
    val tapOutsideSource = remember { MutableInteractionSource() }

    // На finalize показываем success-оверлей и через 900мс выходим — те же
    // тайминги и поведение, что и на 1 Этапе (см. Stage1FormScreen).
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
                .padding(padding)
                // Тап по «пустой» зоне (мимо инпутов) гасит фокус — клавиатура
                // прячется. indication=null убирает ripple, тапы по
                // OutlinedTextField/кнопкам/чипам идут к ним напрямую.
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
            // См. комментарий в Stage1FormScreen: 104dp было впритык, нижние
            // выносные буквы упирались в границу. 116dp даёт ~10dp воздуха.
            val photoButtonHeight = if (isTablet) 128.dp else 116.dp
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
                // titleLarge (~22sp) на узких телефонах не позволял
                // подписи «Фото машины, госномера» влезть в кнопку — текст
                // переносился в 3 строки и упирался в её высоту. titleMedium
                // (~16sp) спокойно умещается в 2 строки.
                MaterialTheme.typography.titleMedium

            // Та же layout-схема, что на Stage1: при подъёме клавиатуры
            // Column ужимается imePadding'ом, кнопка «Завершить 2 Этап» через
            // Box.align(BottomEnd) остаётся в нижнем правом углу окна и
            // перекрывается клавиатурой (как на Stage1). Резерв снизу под
            // кнопку — константный, чтобы последний инпут «Комментарий 2 Этап»
            // не уезжал под кнопку при закрытой клавиатуре.
            val bottomReserve = finalizeButtonHeight + sectionGap + outerPadding

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = outerPadding,
                        end = outerPadding,
                        top = outerPadding,
                        // bottom = 0, иначе между imePadding-зоной Column и
                        // верхом клавиатуры виден белый зазор outerPadding.
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize(),
                ) {
                    // Sticky-зоны: фото + Госномер закреплены сверху, Комментарий
                    // снизу. Скроллится только средняя область со списком
                    // материалов (weight=1f + verticalScroll). imePadding ужимает
                    // всю Column-цепочку при подъёме клавиатуры — Комментарий
                    // оказывается прямо над клавиатурой.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .padding(bottom = bottomReserve),
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
                            // Снимок серверных значений — нужно UI для подсветки
                            // правок (перечёркнутое имя / qty в формате old (new)).
                            originalMaterials = state.originalMaterials,
                            // Поле «Ед.» в модалке правки строки скрыто —
                            // единицу инспектор не меняет. В диалоге «Добавить
                            // материал» (отдельный MaterialEditDialog) оно есть.
                            editingShowUnitField = false,
                        )
                    }

                    // 1 Этап header + Комментарий 2 Этап в одном sub-Column'е без
                    // gap — header «прилипает» к input'у сверху, не съедая
                    // место у списка материалов. Уровень родительского
                    // verticalArrangement.spacedBy(sectionGap) к ним не
                    // применяется (они один child родителя).
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
                            // singleLine для одинаковой высоты с «Госномер» —
                            // тот же стиль, что и Комментарий на 1 Этапе.
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    }

                    // Кнопка «Завершить 2 Этап» — overlay через align(BottomEnd),
                    // вне layout-chain Column. imePadding на Column её не двигает,
                    // при открытой клавиатуре она остаётся внизу окна и просто
                    // перекрывается клавиатурой (как на Stage1).
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
            )
        }

        if (confirmFinalizeVisible) {
            FinalizeConfirmDialog(
                title = "Завершить 2 Этап?",
                message = "Подтверждение МОЛ будет отправлено на сервер. Изменить данные позже нельзя.",
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
    }
}
