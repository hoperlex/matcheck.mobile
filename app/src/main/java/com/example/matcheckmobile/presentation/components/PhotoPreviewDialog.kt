package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Полноэкранный просмотр локального фото с pinch‑zoom и pan. Закрытие — крестик или системный back.
 * Двойной тап сбрасывает масштаб и смещение.
 *
 * Если задан [onDelete] — под превью показывается кнопка «Удалить фото» с
 * подтверждением через AlertDialog. После подтверждения вызывается [onDelete]
 * и затем [onDismiss], чтобы закрыть превью удалённого фото.
 */
@Composable
fun PhotoPreviewDialog(
    filePath: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val configuration = LocalConfiguration.current
        val targetPx = with(LocalDensity.current) {
            maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
        }

        var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }
        var loaded by remember(filePath) { mutableStateOf(false) }
        LaunchedEffect(filePath, targetPx) {
            val decoded = withContext(Dispatchers.IO) {
                decodeOrientedBitmap(filePath, targetPx)?.asImageBitmap()
            }
            bitmap = decoded
            loaded = true
        }

        var scale by remember(filePath) { mutableFloatStateOf(1f) }
        var offsetX by remember(filePath) { mutableFloatStateOf(0f) }
        var offsetY by remember(filePath) { mutableFloatStateOf(0f) }

        var confirmDeleteVisible by remember { mutableStateOf(false) }

        val backgroundInteraction = remember { MutableInteractionSource() }
        val cardInteraction = remember { MutableInteractionSource() }
        val buttonInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clickable(
                    interactionSource = backgroundInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .fillMaxHeight(0.5f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(
                            interactionSource = cardInteraction,
                            indication = null,
                            onClick = {},
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val current = bitmap
                    when {
                        current != null -> {
                            Image(
                                bitmap = current,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                    )
                                    .pointerInput(filePath) {
                                        detectTransformGestures(
                                            panZoomLock = false,
                                        ) { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                                            scale = newScale
                                            if (newScale <= 1.001f) {
                                                offsetX = 0f
                                                offsetY = 0f
                                            } else {
                                                offsetX += pan.x
                                                offsetY += pan.y
                                            }
                                        }
                                    },
                            )
                        }
                        loaded -> {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = "Файл фото не найден",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                        else -> {
                            CircularProgressIndicator()
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // «Удалить фото» под карточкой: красная кнопка с иконкой
                // корзины + подтверждением. Обёрнута в Box с clickable=пусто,
                // чтобы тап по самой кнопке не уходил в фоновый dismiss.
                if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable(
                                interactionSource = buttonInteraction,
                                indication = null,
                                onClick = {},
                            ),
                    ) {
                        Button(
                            onClick = { confirmDeleteVisible = true },
                            modifier = Modifier.height(56.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                text = "  Удалить фото",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        if (confirmDeleteVisible) {
            AlertDialog(
                onDismissRequest = { confirmDeleteVisible = false },
                title = { Text("Удалить фото?", fontWeight = FontWeight.SemiBold) },
                text = { Text("Восстановить удалённое фото нельзя.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDeleteVisible = false
                            onDelete?.invoke()
                            onDismiss()
                        },
                    ) {
                        Text(
                            "Удалить",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteVisible = false }) {
                        Text("Отмена")
                    }
                },
            )
        }
    }
}

