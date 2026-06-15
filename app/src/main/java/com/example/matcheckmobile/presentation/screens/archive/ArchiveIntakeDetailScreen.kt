package com.example.matcheckmobile.presentation.screens.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.presentation.components.RemotePhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.RemotePhotoRef
import com.example.matcheckmobile.presentation.components.RemoteS3PhotoThumb
import com.example.matcheckmobile.presentation.viewmodel.ArchiveIntakeDetailViewModel
import com.example.matcheckmobile.presentation.viewmodel.formatLocalTime
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveIntakeDetailScreen(
    onBack: () -> Unit,
) {
    val vm: ArchiveIntakeDetailViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    // Полноэкранный preview по тапу на миниатюру: набор листающихся фото и
    // стартовый индекс. Перелистывание ограничено той же колонкой (стадия +
    // категория), на которую тапнул пользователь, чтобы «Документ» и «Груз»
    // не перемешивались в одном свайпе.
    var previewPhotos by remember { mutableStateOf<List<RemotePhotoRef>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }
    var showPreview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val plate = state.vehiclePlate ?: "—"
                    Text("Приёмка · $plate")
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
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val outerPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (!state.loaded) {
                        Text(
                            text = "Загружаю…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        state.error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        val openPreview = { photos: List<RemoteDeliveryPhotoEntity>, index: Int ->
                            previewPhotos = photos.map {
                                RemotePhotoRef(photoId = it.id, localBlobPath = it.localBlobPath)
                            }
                            previewIndex = index
                            showPreview = true
                        }

                        StageSection(
                            stageLabel = "1 Этап",
                            count = state.stage1DocumentPhotos.size + state.stage1VehiclePhotos.size,
                            timeLabel = "Время",
                            timeValue = formatLocalTime(state.arrivedAtMs),
                            documentPhotos = state.stage1DocumentPhotos,
                            vehiclePhotos = state.stage1VehiclePhotos,
                            onPhotoClick = openPreview,
                        )

                        StageSection(
                            stageLabel = "2 Этап",
                            count = state.stage2DocumentPhotos.size + state.stage2VehiclePhotos.size,
                            timeLabel = "Время",
                            timeValue = formatLocalTime(state.confirmedAtMs),
                            documentPhotos = state.stage2DocumentPhotos,
                            vehiclePhotos = state.stage2VehiclePhotos,
                            onPhotoClick = openPreview,
                        )
                    }
                }
            }
        }

        if (showPreview && previewPhotos.isNotEmpty()) {
            RemotePhotoPreviewDialog(
                photos = previewPhotos,
                initialIndex = previewIndex,
                onDismiss = {
                    showPreview = false
                    previewPhotos = emptyList()
                    previewIndex = 0
                },
            )
        }
    }
}

@Composable
private fun StageSection(
    stageLabel: String,
    count: Int,
    timeLabel: String,
    timeValue: String?,
    documentPhotos: List<RemoteDeliveryPhotoEntity>,
    vehiclePhotos: List<RemoteDeliveryPhotoEntity>,
    onPhotoClick: (List<RemoteDeliveryPhotoEntity>, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$stageLabel ($count)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (timeValue != null) {
                Text(
                    text = "    $timeLabel: $timeValue",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoColumn(
                caption = "Документ",
                photos = documentPhotos,
                onPhotoClick = onPhotoClick,
                modifier = Modifier.weight(1f),
            )
            PhotoColumn(
                caption = "Груз/машина",
                photos = vehiclePhotos,
                onPhotoClick = onPhotoClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PhotoColumn(
    caption: String,
    photos: List<RemoteDeliveryPhotoEntity>,
    onPhotoClick: (List<RemoteDeliveryPhotoEntity>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photos.isEmpty()) {
            // Empty-state — лаконичная плашка, чтобы строка с подписями
            // оставалась горизонтально выровненной с соседней колонкой.
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
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items = photos, key = { _, p -> p.id }) { idx, p ->
                    RemoteS3PhotoThumb(
                        photoId = p.id,
                        localBlobPath = p.localBlobPath,
                        size = 96.dp,
                        thumb = true,
                        onClick = { onPhotoClick(photos, idx) },
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
