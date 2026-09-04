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
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.presentation.components.RemotePhotoPreviewDialog
import com.example.matcheckmobile.presentation.components.RemotePhotoRef
import com.example.matcheckmobile.presentation.components.RemoteS3PhotoThumb
import com.example.matcheckmobile.presentation.util.formatLocalTime
import com.example.matcheckmobile.presentation.viewmodel.ArchiveDispatchDetailViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveDispatchDetailScreen(
    onBack: () -> Unit,
) {
    val vm: ArchiveDispatchDetailViewModel = matcheckViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    var previewPhotos by remember { mutableStateOf<List<RemotePhotoRef>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }
    var showPreview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val plate = state.vehiclePlate ?: "—"
                    Text("Отгрузка · $plate")
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

                        val openPreview = { photos: List<RemoteShipmentPhotoEntity>, index: Int ->
                            previewPhotos = photos.map {
                                RemotePhotoRef(
                                    photoId = it.id,
                                    localBlobPath = it.localBlobPath,
                                    localThumbPath = it.localThumbPath,
                                    sourcePath = it.sourcePath,
                                )
                            }
                            previewIndex = index
                            showPreview = true
                        }

                        // 1 Этап — время выезда (`shippedAt`). Это симметрия с
                        // приёмочным `arrivedAt`: момент «Завершить 1 Этап»
                        // ставит его в DispatchStage1FormViewModel.finalizeStage1.
                        StageSection(
                            stageLabel = "1 Этап",
                            count = state.stage1DocumentPhotos.size + state.stage1VehiclePhotos.size,
                            timeValue = formatLocalTime(state.shippedAtMs),
                            documentPhotos = state.stage1DocumentPhotos,
                            vehiclePhotos = state.stage1VehiclePhotos,
                            onPhotoClick = openPreview,
                        )

                        // 2 Этап — время подтверждения МОЛ (`confirmedByMolAt`).
                        StageSection(
                            stageLabel = "2 Этап",
                            count = state.stage2DocumentPhotos.size + state.stage2VehiclePhotos.size,
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
    timeValue: String?,
    documentPhotos: List<RemoteShipmentPhotoEntity>,
    vehiclePhotos: List<RemoteShipmentPhotoEntity>,
    onPhotoClick: (List<RemoteShipmentPhotoEntity>, Int) -> Unit,
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
                    text = "    Время: $timeValue",
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
    photos: List<RemoteShipmentPhotoEntity>,
    onPhotoClick: (List<RemoteShipmentPhotoEntity>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photos.isEmpty()) {
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
                        localThumbPath = p.localThumbPath,
                        sourcePath = p.sourcePath,
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
