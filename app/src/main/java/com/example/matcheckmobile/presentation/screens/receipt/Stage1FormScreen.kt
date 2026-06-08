package com.example.matcheckmobile.presentation.screens.receipt

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
import com.example.matcheckmobile.presentation.components.FinalizeConfirmDialog
import com.example.matcheckmobile.presentation.components.FinalizeSuccessOverlay
import com.example.matcheckmobile.presentation.components.MaterialsField
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.VehicleLoadInfo
import com.example.matcheckmobile.presentation.components.VehicleTypeChips
import com.example.matcheckmobile.presentation.components.rememberDocumentScanner
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
    // Колбэк удаления именно для текущего превью — секция (документы/груз)
    // передаёт сюда замыкание, удаляющее своё фото. Кнопка «Удалить» внутри
    // PhotoPreviewDialog дёргает его после подтверждения.
    var previewDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmFinalizeVisible by remember { mutableStateOf(false) }
    var successVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Один источник для невидимого clickable на «пустые» зоны экрана — нужен
    // чтобы тап мимо инпутов сворачивал клавиатуру через clearFocus.
    val tapOutsideSource = remember { MutableInteractionSource() }

    // Только при успешной финализации показываем «успешный» оверлей и
    // навигируемся назад — ошибка не trigger'ит, она пойдёт через Snackbar.
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
    val takeCargoPhoto = rememberPhotoCapture(
        photoStorage = container.photoStorage,
        onPhotoTaken = vm::onCargoPhotoTaken,
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
                // Тап по «пустой» части экрана (мимо инпутов) гасит фокус и
                // прячет клавиатуру. indication=null убирает ripple, тапы по
                // OutlinedTextField/кнопкам идут к ним напрямую.
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
            // На телефоне 104dp было впритык: padding 24 + иконка 32 + gap 6 +
            // 2 строки текста ~36 = ~98, нижние выносные элементы букв («у», «р»)
            // упирались в нижнюю границу кнопки. 116dp даёт запас ~10dp.
            val photoButtonHeight = if (isTablet) 128.dp else 116.dp
            val vehicleIconHeight = if (isTablet) 96.dp else 72.dp
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
                // titleLarge (~22sp) на узких телефонах не позволял
                // подписи «Фото груза, госномера» влезть в кнопку — текст
                // переносился в 3 строки и упирался в её высоту. titleMedium
                // (~16sp) спокойно умещается в 2 строки.
                MaterialTheme.typography.titleMedium
            val materialsButtonTextStyle = if (isTablet)
                MaterialTheme.typography.headlineSmall
            else
                MaterialTheme.typography.titleLarge
            val vehicleLabelStyle = if (isTablet)
                MaterialTheme.typography.titleLarge
            else
                MaterialTheme.typography.titleMedium
            val vehicleChipTitleStyle = if (isTablet)
                MaterialTheme.typography.titleLarge
            else
                MaterialTheme.typography.titleMedium

            // Layout-логика:
            // - Комментарий — последний элемент общего скролла, чтобы при
            //   закрытой клавиатуре он скроллился со всем остальным контентом.
            // - imePadding на скролл-Column: при поднятии клавиатуры viewport
            //   скролла обрезается её верхней границей.
            // - padding(bottom) внутри скролла = резерв под кнопку «Завершить»,
            //   но ТОЛЬКО когда клавиатуры нет. При открытой ime он = 0:
            //   иначе под Комментарием остаётся пустая зона, которую можно
            //   проскроллить — и тогда Комментарий уезжает выше клавиатуры,
            //   образуя белую полосу. Со scrollReserve=0 максимум скролла как
            //   раз прижимает Комментарий к верху клавиатуры и выше не пускает.
            // - Кнопка через Box.align(BottomEnd) — overlay, ime её не двигает.
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
                        // bottom = 0 — иначе появляется белый зазор между
                        // imePadding-зоной и низом экрана (= верхом клавиатуры).
                        // Нижний отступ для кнопки даём в её Box.align ниже.
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
                            // Госномер пишем ВЕРХНИМ регистром, как и раньше с
                            // forceUppercase в модалке — иначе при сравнении и
                            // валидации регистры разъедутся.
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
                        }

                        // Блок «Тип транспорта» показываем только для приёмки из
                        // подгруженной УПД. В ручной приёмке («Создать приёмку»,
                        // updId == null) он не нужен — по запросу скрыт.
                        if (state.updId != null) {
                            VehicleTypeChips(
                                selectedCode = state.vehicleTypeCode,
                                onSelected = {
                                    // Тап по чипу транспорта так же прячет клавиатуру,
                                    // как и тап по пустой зоне — чип съедает pointer-event
                                    // до корневого clickable, поэтому clearFocus вызываем
                                    // здесь явно.
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
                                    // Чекбокс «Транзит» справа от «Тип транспорта»
                                    // header'а — для picked-UPD сценария. Empty-draft
                                    // показывает чекбокс ниже комментария (см. дальше).
                                    androidx.compose.foundation.layout.Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            focusManager.clearFocus()
                                            vm.setInTransit(!state.inTransit)
                                        },
                                    ) {
                                        androidx.compose.material3.Checkbox(
                                            checked = state.inTransit,
                                            onCheckedChange = { vm.setInTransit(it) },
                                        )
                                        Text(
                                            text = "Транзит",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                },
                            )
                        }

                        MaterialsField(
                            value = state.materials,
                            onValueChange = vm::setMaterials,
                            readOnly = true,
                            buttonTextStyle = materialsButtonTextStyle,
                            buttonMinHeight = if (isTablet) 72.dp else 64.dp,
                            // Телефон менеджера-автора УПД: на 1 Этапе в шапке модалки
                            // «Материалы» появляется иконка звонка. Только если УПД
                            // загружена с веб-портала (НЕ из EDO/mail) и у автора
                            // указан телефон — иначе null и иконка не рисуется.
                            managerPhone = state.managerPhone,
                        )

                        OutlinedTextField(
                            value = state.commentText,
                            onValueChange = vm::setComment,
                            label = { Text("Комментарий", style = inputLabelStyle) },
                            // singleLine для визуальной унификации с «Госномер»
                            // и «Введите УПД» — все три инпута одинаковой высоты.
                            // Длинный текст прокручивается горизонтально внутри поля.
                            singleLine = true,
                            textStyle = inputTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Чекбокс «Транзит» для empty-draft Приёмки. На picked-UPD
                        // сценарии чекбокс находится справа от «Тип транспорта»
                        // (см. VehicleTypeChips trailingHeader выше).
                        if (state.updId == null) {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        focusManager.clearFocus()
                                        vm.setInTransit(!state.inTransit)
                                    },
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = state.inTransit,
                                    onCheckedChange = { vm.setInTransit(it) },
                                )
                                Text(
                                    text = "Транзит",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }

                    // Кнопка «Завершить 1 Этап» — overlay через align(BottomEnd),
                    // вне layout-chain Column. imePadding на Column её не двигает,
                    // при открытой клавиатуре она остаётся внизу окна и просто
                    // перекрывается клавиатурой (как на Stage2).
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
                                text = if (state.isSaving) "Сохранение..." else "Завершить 1 Этап",
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

        if (confirmFinalizeVisible) {
            FinalizeConfirmDialog(
                title = "Завершить 1 Этап?",
                onConfirm = {
                    confirmFinalizeVisible = false
                    vm.finalizeStage1()
                },
                onDismiss = { confirmFinalizeVisible = false },
            )
        }

        if (successVisible) {
            FinalizeSuccessOverlay()
        }
    }
}

