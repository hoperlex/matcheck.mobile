package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Полноэкранный просмотр локального фото с pinch‑zoom и pan. Закрытие — крестик или системный back.
 * Двойной тап сбрасывает масштаб и смещение.
 */
@Composable
fun PhotoPreviewDialog(filePath: String, onDismiss: () -> Unit) {
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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
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
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
                else -> {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x66FFFFFF)),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }
    }
}

