package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
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

        if (confirmFinalizeVisible) {
            FinalizeConfirmDialog(
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

/**
 * Подтверждение «Завершить 1 Этап». Material 3 AlertDialog даёт компактный
 * вид без лишней высоты: заголовок + одна строка пояснения + две кнопки.
 */
@Composable
private fun FinalizeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Завершить 1 Этап?", fontWeight = FontWeight.SemiBold) },
        text = {
            Text("Данные будут отправлены на сервер. Изменить их позже на 1 Этапе нельзя.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Завершить", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Короткий оверлей «Успешно» поверх формы: зелёный круг с галочкой, spring
 * scale-in от 0.6 до 1.0 + fade. Показывается ~700-900мс, после чего экран
 * автоматически закрывается (delay в LaunchedEffect Stage1FormScreen).
 *
 * Триггерится ТОЛЬКО при `state.finalized == true` — ошибка идёт через
 * Snackbar, без оверлея.
 */
@Composable
private fun FinalizeSuccessOverlay() {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "success-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "success-alpha",
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(RoundedCornerShape(28.dp))
                .background(SuccessGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Успешно",
                tint = Color.White,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

private val SuccessGreen = Color(0xFF2E7D32) // green 800 — совпадает с «Начато»
