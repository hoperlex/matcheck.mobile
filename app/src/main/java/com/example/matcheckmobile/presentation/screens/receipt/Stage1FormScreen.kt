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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.ui.platform.LocalFocusManager
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
import com.example.matcheckmobile.presentation.components.PhotoCaptureSection
import com.example.matcheckmobile.presentation.components.PhotoPreviewDialog
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
    val focusManager = LocalFocusManager.current
    // Один источник для невидимого clickable на «пустые» зоны экрана — нужен
    // чтобы тап мимо инпутов сворачивал клавиатуру через clearFocus.
    val tapOutsideSource = remember { MutableInteractionSource() }

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
            val photoButtonHeight = if (isTablet) 128.dp else 104.dp
            val vehicleIconHeight = if (isTablet) 96.dp else 72.dp
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

            // Layout-трюк, чтобы при поднятии клавиатуры:
            // 1) скролл доходил до Комментария над клавиатурой,
            // 2) кнопка «Завершить 1 Этап» оставалась внизу (клавиатура её
            //    перекроет — поднимать её не надо),
            // 3) между скроллом и клавиатурой не было видимой белой полосы.
            //
            // Решение: скролл-Column тянется на ВСЮ высоту (включая зону под
            // клавиатурой) и имеет imePadding — empty-зона imePadding физически
            // совпадает с зоной клавиатуры и не видна. Кнопка рендерится через
            // Box.align(BottomEnd) поверх — она в нижнем правом углу окна
            // независимо от ime. У скролла внизу — content-padding под кнопку,
            // чтобы последний инпут не «уезжал» под неё при ime=0.
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
                            // Резерв снизу под кнопку + её внутренний gap +
                            // outerPadding — чтобы последний инпут (Комментарий)
                            // не упирался в кнопку, когда клавиатура закрыта.
                            .padding(bottom = finalizeButtonHeight + sectionGap + outerPadding),
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
                                buttonText = "Фото груза, госномера",
                                buttonTextStyle = photoButtonTextStyle,
                                isTablet = isTablet,
                                buttonHeight = photoButtonHeight,
                                onTakePhoto = takeCargoPhoto,
                                photoPaths = state.cargoPhotoPaths,
                                onRemovePhoto = vm::removeCargoPhoto,
                                onPreviewPhoto = { previewPath = it },
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
                            loadInfo = state.loadInfo,
                            labelStyle = vehicleLabelStyle,
                            chipTitleStyle = vehicleChipTitleStyle,
                        )

                        MaterialsField(
                            value = state.materials,
                            onValueChange = vm::setMaterials,
                            readOnly = true,
                            buttonTextStyle = materialsButtonTextStyle,
                            buttonMinHeight = if (isTablet) 72.dp else 64.dp,
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
                    }

                    // Кнопка «Завершить 1 Этап» прибита к низу окна через
                    // Box.align(BottomEnd). Не входит в layout-chain scroll-Column,
                    // поэтому imePadding скролла её не двигает — при подъёме
                    // клавиатуры она остаётся в той же позиции (клавиатура её
                    // перекрывает; для нажатия — сначала закрыть тапом мимо).
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = outerPadding),
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
    }
}

