package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncQueueViewModel(private val container: AppContainer) : ViewModel() {
    val pendingSessions: StateFlow<List<ReceiptSessionEntity>> =
        container.database.receiptSessionDao()
            .observeBySyncStatuses(OperationRepository.UNSYNCED)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val pendingOperations: StateFlow<List<MaterialOperationEntity>> =
        container.operationRepository.observeUnsynced().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val pendingAttachments: StateFlow<List<OperationAttachmentEntity>> =
        container.attachmentRepository.observePending().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Фото 1/2 Этапа в любом «незавершённом» состоянии — для отображения в
     * Очереди синхронизации со статусом и lastUploadError. Помогает понять,
     * что именно сломалось на этапе photo upload (presign / S3 PUT / confirm).
     */
    val pendingDeliveryPhotos: StateFlow<List<RemoteDeliveryPhotoEntity>> =
        container.database.remoteDeliveryDao()
            .observePhotosByStatus(PHOTO_PENDING_STATUSES)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val pendingShipmentPhotos: StateFlow<List<RemoteShipmentPhotoEntity>> =
        container.database.remoteShipmentDao()
            .observePhotosByStatus(PHOTO_PENDING_STATUSES)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * Все мутации (включая `delivery`/`shipment` upsert и mark-deletion).
     * Показываем целиком — чтобы видеть и conflictPending, и lastError, и
     * число attempts. Помогает понять, доехала ли свежесозданная приёмка
     * до сервера и почему её там нет.
     */
    val mutations: StateFlow<List<MutationEntity>> =
        container.database.mutationDao().observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun retryNow() {
        viewModelScope.launch {
            // Сначала чистим «зомби»-мутации с conflictPending от create-on-existing
            // (network-glitch на первом push → 409 при повторе). Без этого
            // 23 такие записи останутся в очереди навсегда и заблокируют push
            // других мутаций с теми же entityId.
            runCatching { container.mutationProcessor.resolveStaleCreateConflicts() }
            // Затем общий push-pull для свежих приёмок/выездов.
            MatcheckSyncScheduler.requestImmediateSync(container.appContext)
        }
    }

    private companion object {
        val PHOTO_PENDING_STATUSES = listOf("PENDING_UPLOAD", "UPLOADING", "UPLOAD_ERROR")
    }
}
