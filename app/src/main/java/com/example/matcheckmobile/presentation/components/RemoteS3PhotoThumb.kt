package com.example.matcheckmobile.presentation.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.media.PhotoFrameCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Превью фото приёмки/отгрузки. Источник выбирается цепочкой «локальное первым»:
 *
 * `localThumbPath` → `localBlobPath` → `sourcePath` → скачивание из S3 (с
 * дисковым кэшем внутри [com.example.matcheckmobile.media.PhotoBytesLoader]).
 *
 * Порядок не случаен и решает две разные проблемы. Своя миниатюра 320px
 * переживает отправку в S3 (её больше не удаляют после UPLOADED), поэтому фото
 * со своего планшета видно офлайн и даже если объект пропал из хранилища.
 * Звено `sourcePath` закрывает кадры, ещё не прошедшие подготовку: у них
 * `localBlobPath` пуст по определению, а серверного id ещё нет — раньше такие
 * гарантированно показывались как «фото недоступно».
 *
 * Тап по успешно загруженному кадру открывает просмотр; тап по заглушке ошибки
 * повторяет загрузку — иначе одна осечка сети требовала выйти с экрана и
 * вернуться.
 */
@Composable
fun RemoteS3PhotoThumb(
    photoId: String,
    localBlobPath: String?,
    modifier: Modifier = Modifier,
    localThumbPath: String? = null,
    sourcePath: String? = null,
    size: Dp = 96.dp,
    thumb: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as MatcheckApplication).container }
    val targetPx = with(LocalDensity.current) { size.roundToPx() }

    var image by remember(photoId) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(photoId) { mutableStateOf(false) }
    // Счётчик ретраев — ключ LaunchedEffect: тап по заглушке перезапускает загрузку.
    var retryTick by remember(photoId) { mutableIntStateOf(0) }

    LaunchedEffect(photoId, localThumbPath, localBlobPath, sourcePath, thumb, retryTick) {
        loaded = false
        image = withContext(Dispatchers.IO) {
            val local = localSourceFor(thumb, localThumbPath, localBlobPath, sourcePath)
            if (local != null) {
                decodeOrientedBitmap(local, targetPx)?.asImageBitmap()
            } else {
                PhotoFrameCache.get(photoId, thumb)
                    ?: container.photoBytesLoader.load(photoId, thumb = thumb)
                        ?.let { decodeBytes(it, targetPx) }
                        ?.asImageBitmap()
                        ?.also { PhotoFrameCache.put(photoId, thumb, it) }
            }
        }
        loaded = true
    }

    val current = image
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { m ->
                when {
                    current != null && onClick != null -> m.clickable(onClick = onClick)
                    // Заглушка ошибки кликабельна всегда: это повтор, а не просмотр.
                    current == null && loaded -> m.clickable { retryTick++ }
                    else -> m
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            current != null -> Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            !loaded -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            else -> Icon(
                imageVector = com.example.matcheckmobile.ui.icons.LocalIcons.BrokenImage,
                contentDescription = "Фото недоступно, нажмите чтобы повторить",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Первый существующий локальный файл по приоритету режима.
 *
 * Для миниатюры сохранённый thumb предпочтительнее: он ровно под размер плитки
 * и на порядок легче. Для полного просмотра порядок обратный — оригинал важнее,
 * а thumb остаётся мгновенной заглушкой, чтобы офлайн открытие вообще работало.
 */
internal fun localSourceFor(
    thumb: Boolean,
    localThumbPath: String?,
    localBlobPath: String?,
    sourcePath: String?,
): String? {
    val order = if (thumb) {
        listOf(localThumbPath, localBlobPath, sourcePath)
    } else {
        listOf(localBlobPath, sourcePath, localThumbPath)
    }
    return order.firstOrNull { path -> path != null && File(path).exists() }
}

/**
 * Декод из байтов с тем же принципом inSampleSize, что и [decodeOrientedBitmap]
 * для файла. Общий для плитки и полноэкранного просмотра.
 */
internal fun decodeBytes(bytes: ByteArray, targetPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (targetPx > 0 && w / (sample * 2) >= targetPx && h / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
