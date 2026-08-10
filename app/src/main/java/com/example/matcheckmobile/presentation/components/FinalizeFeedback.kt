package com.example.matcheckmobile.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.matcheckmobile.MatcheckApplication

/**
 * Подтверждение финализации этапа («Завершить N Этап?»). Material 3 AlertDialog
 * с компактным видом: заголовок + одна строка пояснения + две кнопки.
 * Используется и на 1 Этапе, и на 2 Этапе с разными текстами.
 */
@Composable
fun FinalizeConfirmDialog(
    title: String,
    message: String? = null,
    confirmLabel: String = "Завершить",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // Защита от double-tap: пока идёт сохранение, повторный тап на «Завершить»
    // блокируется на уровне UI, не попадает в viewModelScope.launch. Guard
    // в VM (`if (cur.isSaving) return`) остаётся вторым слоем защиты.
    // По умолчанию true — существующие вызовы без параметра работают как
    // раньше.
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        // message=null → диалог без пояснительного текста (минимальная высота:
        // заголовок + кнопки). Используется на 1 Этапе приёмки, где пояснение
        // избыточно.
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Хост success-оверлея. Ставится ОДИН раз в корне Activity рядом с
 * [AppUpdateDialogHost] и наблюдает за [FinalizeFeedbackController].
 *
 * Почему не внутри формы, как было раньше: композиция формы уходит вместе с
 * навигацией, и если та не отработала, оверлей оставался висеть без всякой
 * возможности его закрыть. Композиция MainActivity навигацией не диспозится —
 * осиротить оверлей больше нечем.
 */
@Composable
fun FinalizeSuccessHost() {
    val app = LocalContext.current.applicationContext as? MatcheckApplication ?: return
    val controller = app.container.finalizeFeedback
    val shown by controller.state.collectAsState()
    if (shown != null) {
        FinalizeSuccessOverlay(onDismiss = { controller.hide("user") })
    }
}

/**
 * Короткий «успех»-оверлей: зелёная карточка с галочкой, spring scale-in от
 * 0.6 до 1.0 + fade. Держится ~900мс силами [FinalizeFeedbackController] и
 * гаснет сам. Идентичен на 1 и 2 Этапе.
 *
 * Три способа закрыть вручную — карточка, тап мимо неё и системный Back. Это
 * страховка: пока оверлей висит, он перехватывает весь ввод, поэтому без
 * аварийного выхода любой сбой таймера запирал инспектора намертво.
 */
@Composable
fun FinalizeSuccessOverlay(onDismiss: () -> Unit) {
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
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
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
                .background(SuccessGreen)
                // dismissOnClickOutside реагирует только на тап ВНЕ границ
                // контента, поэтому саму карточку делаем кликабельной отдельно.
                // indication = null — чтобы визуал не изменился ни на пиксель.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .testTag(SUCCESS_OVERLAY_TAG),
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

/** Тег для UI-тестов: карточка галочки. */
const val SUCCESS_OVERLAY_TAG = "finalize_success_overlay"

private val SuccessGreen = Color(0xFF2E7D32) // green 800 — совпадает с «Начато»
