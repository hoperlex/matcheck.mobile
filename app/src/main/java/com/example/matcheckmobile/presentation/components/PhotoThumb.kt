package com.example.matcheckmobile.presentation.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Миниатюра локального фото. Если задан [onRemove] — рисуется крестик
 * удаления в правом верхнем углу; иначе только превью без X (тогда удаление
 * выносится в [PhotoPreviewDialog] с подтверждением).
 * Декодирование bitmap'а на IO‑диспетчере, с уменьшением по `inSampleSize` и поворотом по EXIF.
 */
@Composable
fun PhotoThumb(
    filePath: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    size: androidx.compose.ui.unit.Dp = 80.dp,
    onClick: (() -> Unit)? = null,
) {
    var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(filePath) { mutableStateOf(false) }
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    LaunchedEffect(filePath, targetPx) {
        val decoded = withContext(Dispatchers.IO) {
            decodeOrientedBitmap(filePath, targetPx)?.asImageBitmap()
        }
        bitmap = decoded
        loaded = true
    }
    Box(
        modifier = modifier.size(size + 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (loaded) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = "Файл фото не найден",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Удалить фото",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Декодирует JPEG в bitmap с `inSampleSize`, ограничивая длинную сторону `targetPx`,
 * и применяет поворот по EXIF. Доступно и для миниатюр, и для превью.
 */
internal fun decodeOrientedBitmap(path: String, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (w / (sample * 2) >= targetPx && h / (sample * 2) >= targetPx) sample *= 2
    val raw = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            else -> Unit
        }
    }
    return if (matrix.isIdentity) raw
    else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
        if (it !== raw) raw.recycle()
    }
}
