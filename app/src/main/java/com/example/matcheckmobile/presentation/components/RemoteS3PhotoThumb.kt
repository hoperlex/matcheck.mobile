package com.example.matcheckmobile.presentation.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.PhotoFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Превью фото из «remote_delivery_photos». Источник зависит от стадии:
 *
 * - Если у записи ещё есть `localBlobPath` (фото не успело уйти в S3) —
 *   читаем локальный файл существующим декодером [decodeOrientedBitmap].
 * - Иначе тянем presigned URL через [PhotoFetcher] и декодируем поток.
 *   Coil/Glide в проекте не подключены — лишних зависимостей не добавляем,
 *   используем тот же подход, что и [PhotoThumb] для локальных файлов.
 *
 * Загрузка идёт на IO‑диспетчере один раз на каждый `photoId` (re-launch
 * только при смене id). Кэш presigned URL обеспечивает сам [PhotoFetcher].
 */
@Composable
fun RemoteS3PhotoThumb(
    photoId: String,
    localBlobPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    thumb: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val photoFetcher = remember {
        (context.applicationContext as MatcheckApplication).container.photoFetcher
    }
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }

    var image by remember(photoId) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(photoId) { mutableStateOf(false) }

    LaunchedEffect(photoId, localBlobPath, thumb) {
        image = withContext(Dispatchers.IO) {
            // 1) Local-first — пока pipeline не выгрузил фото в S3, у нас
            //    есть актуальный blob под рукой.
            val local = localBlobPath?.let(::File)?.takeIf { it.exists() }
            if (local != null) {
                return@withContext decodeOrientedBitmap(local.absolutePath, targetPx)?.asImageBitmap()
            }
            // 2) Иначе — presigned URL → байты → bitmap. inSampleSize по
            //    тому же принципу, что и в decodeOrientedBitmap для файла:
            //    делаем grow-down до близкого к targetPx размера.
            runCatching {
                val url = photoFetcher.getDisplayUrl(photoId, thumb = thumb).getOrThrow()
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

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        contentAlignment = Alignment.Center,
    ) {
        val current = image
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
                imageVector = Icons.Default.BrokenImage,
                contentDescription = "Фото недоступно",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
