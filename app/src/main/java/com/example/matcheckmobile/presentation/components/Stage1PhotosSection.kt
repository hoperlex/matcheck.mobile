package com.example.matcheckmobile.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Элемент списка фото 1-го Этапа для отображения на форме 2-го Этапа.
 * Содержит ссылки, нужные [RemoteS3PhotoThumb] (id + опциональный локальный
 * blob), и метаданные для подписи/времени.
 */
data class Stage1PhotoItem(
    val photoId: String,
    val localBlobPath: String?,
    /** Категория фото для подписи под колонкой ("Документ" / "Груз/машина"). */
    val captionLabel: String,
    /** Момент съёмки в локальной зоне устройства, для tooltip/доп. подписи. */
    val takenAtMs: Long? = null,
)

/**
 * Раскрывающийся блок «1 Этап» на форме подтверждения 2-го Этапа. Зеркалит
 * визуальный стиль [ContractorHeaderCard]: surfaceVariant-фон, бейдж с
 * количеством, chevron-стрелка. По умолчанию свёрнут — инспектор раскрывает,
 * чтобы пересмотреть фото 1-го Этапа перед подтверждением.
 *
 * Сам компонент **не** хостит preview-диалог — клик пробрасывает в [onPhotoClick]
 * выбранное фото и **всю свою колонку** (Документ либо Груз/машина), чтобы
 * родитель открыл [RemotePhotoPreviewDialog] с листанием в рамках одной
 * категории.
 *
 * @param documentPhotos фото с `kind='document'`.
 * @param vehiclePhotos фото с любым другим `kind` (cargo/vehicle/other).
 * @param stage1TimeLabel формат «HH:mm» — момент завершения 1-го Этапа.
 *   `null` — время не показываем (например, поле в БД пустое).
 */
@Composable
fun Stage1PhotosSection(
    documentPhotos: List<Stage1PhotoItem>,
    vehiclePhotos: List<Stage1PhotoItem>,
    stage1TimeLabel: String?,
    onPhotoClick: (clicked: Stage1PhotoItem, columnPhotos: List<Stage1PhotoItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalCount = documentPhotos.size + vehiclePhotos.size
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "stage1-photos-chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "1 Этап",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (stage1TimeLabel != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Время: $stage1TimeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Text(
                        text = totalCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            if (totalCount == 0) {
                // Пустое состояние — компактная плашка, чтобы экран не «прыгал»
                // на пустом блоке. Шапка остаётся (count=0) — инспектор видит,
                // что 1 Этап действительно не оставил фото.
                Text(
                    text = "Фото 1 этапа отсутствуют",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Stage1PhotoColumn(
                        caption = "Документ",
                        photos = documentPhotos,
                        onPhotoClick = onPhotoClick,
                        modifier = Modifier.weight(1f),
                    )
                    Stage1PhotoColumn(
                        caption = "Груз/машина",
                        photos = vehiclePhotos,
                        onPhotoClick = onPhotoClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stage1PhotoColumn(
    caption: String,
    photos: List<Stage1PhotoItem>,
    onPhotoClick: (clicked: Stage1PhotoItem, columnPhotos: List<Stage1PhotoItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photos.isEmpty()) {
            // Пустая колонка — оставляем подпись + плейсхолдер той же
            // высоты, что и миниатюра, чтобы соседняя колонка не съезжала.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(width = 1.dp, height = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(photos, key = { _, p -> p.photoId }) { _, p ->
                    RemoteS3PhotoThumb(
                        photoId = p.photoId,
                        localBlobPath = p.localBlobPath,
                        size = 96.dp,
                        thumb = true,
                        onClick = { onPhotoClick(p, photos) },
                    )
                }
            }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
