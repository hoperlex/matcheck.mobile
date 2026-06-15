package com.example.matcheckmobile.presentation.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.PhotoFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Минимальный ref для preview: id фото + опциональный путь к локальному blob'у. */
data class RemotePhotoRef(
    val photoId: String,
    val localBlobPath: String?,
)

/**
 * Полноэкранный просмотр S3-фото с pinch-zoom/pan и перелистыванием по
 * кнопкам «← / →». Закрывается крестиком, тапом вне фото или системным back.
 *
 * Декодирование зеркалит [RemoteS3PhotoThumb]: если есть локальный blob —
 * читаем файл, иначе тянем presigned URL через [PhotoFetcher] (`thumb=false`,
 * чтобы открывался оригинал, а не сжатая миниатюра). Coil/Glide в проекте
 * не подключены — переиспользуем существующий [decodeOrientedBitmap].
 */
@Composable
fun RemotePhotoPreviewDialog(
    photos: List<RemotePhotoRef>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return
    val safeInitial = initialIndex.coerceIn(0, photos.lastIndex)

    val context = LocalContext.current
    val photoFetcher = remember {
        (context.applicationContext as MatcheckApplication).container.photoFetcher
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val configuration = LocalConfiguration.current
        val targetPx = with(LocalDensity.current) {
            maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
        }

        var currentIndex by remember { mutableIntStateOf(safeInitial) }
        val current = photos[currentIndex]

        var bitmap by remember(current.photoId) { mutableStateOf<ImageBitmap?>(null) }
        var loaded by remember(current.photoId) { mutableStateOf(false) }
        LaunchedEffect(current.photoId, current.localBlobPath, targetPx) {
            bitmap = withContext(Dispatchers.IO) {
                val local = current.localBlobPath?.let(::File)?.takeIf { it.exists() }
                if (local != null) {
                    return@withContext decodeOrientedBitmap(local.absolutePath, targetPx)?.asImageBitmap()
                }
                runCatching {
                    val url = photoFetcher.getDisplayUrl(current.photoId, thumb = false).getOrThrow()
                    val bytes = URL(url).openStream().use { it.readBytes() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val w = bounds.outWidth
                    val h = bounds.outHeight
                    if (w <= 0 || h <= 0) return@runCatching null
                    var sample = 1
                    while (w / (sample * 2) >= targetPx && h / (sample * 2) >= targetPx) sample *= 2
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.asImageBitmap()
                }.getOrNull()
            }
            loaded = true
        }

        // Reset transform при переключении между фото.
        var scale by remember(current.photoId) { mutableFloatStateOf(1f) }
        var offsetX by remember(current.photoId) { mutableFloatStateOf(0f) }
        var offsetY by remember(current.photoId) { mutableFloatStateOf(0f) }

        val backgroundInteraction = remember { MutableInteractionSource() }
        val cardInteraction = remember { MutableInteractionSource() }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        // На большой высоте Dialog даёт почти весь экран —
                        // 0.7 оставляет аккуратный воздух сверху/снизу и место
                        // под кнопки перелистывания. 0.5 (как в legacy-диалоге)
                        // на больших фото обрезался по высоте.
                        .fillMaxHeight(0.7f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(
                            interactionSource = cardInteraction,
                            indication = null,
                            onClick = {},
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    when {
                        bmp != null -> {
                            Image(
                                bitmap = bmp,
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
                                    .pointerInput(current.photoId) {
                                        detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
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
                        loaded -> Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "Фото недоступно",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        else -> CircularProgressIndicator()
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Перелистывание + индикатор. Видно всегда (даже если фото
                // одно — индикатор «1 / 1» подсказывает, что других в наборе
                // нет, кнопки в этом случае задизейблены).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canPrev = currentIndex > 0
                    val canNext = currentIndex < photos.lastIndex
                    NavArrowButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Предыдущее фото",
                        enabled = canPrev,
                        onClick = { if (canPrev) currentIndex-- },
                    )
                    Text(
                        text = "${currentIndex + 1} / ${photos.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    NavArrowButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Следующее фото",
                        enabled = canNext,
                        onClick = { if (canNext) currentIndex++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(container),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
            tint = Color.Unspecified,
        )
    }
}
