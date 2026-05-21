package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
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

    fun retryNow() {
        // Раньше дёргали legacy SyncScheduler → OperationSyncWorker, который
        // пушил остаточные ReceiptSession через api.sendSession() и сервер
        // создавал мусорные приёмки со статусом not_filled. Теперь крутим
        // только новый push-pull для Delivery/Shipment.
        MatcheckSyncScheduler.requestImmediateSync(container.appContext)
    }

    private companion object {
        val PHOTO_PENDING_STATUSES = listOf("PENDING_UPLOAD", "UPLOADING", "UPLOAD_ERROR")
    }
}
