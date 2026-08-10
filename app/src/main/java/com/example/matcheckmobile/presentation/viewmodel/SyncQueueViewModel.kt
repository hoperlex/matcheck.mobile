package com.example.matcheckmobile.presentation.viewmodel

import android.content.Intent
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Фото записей ЧУЖОГО объекта: сервер отверг их с 403 foreign_site,
     * автоматический ретрай выключен навсегда. Показываем отдельной секцией —
     * их судьбу решает человек: сначала «Сохранить» (экспорт файлов), потом
     * «Удалить локально». `clearQueue()` их не трогает.
     */
    val quarantinedDeliveryPhotos: StateFlow<List<RemoteDeliveryPhotoEntity>> =
        container.foreignSiteQuarantine.observeDeliveryPhotos().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val quarantinedShipmentPhotos: StateFlow<List<RemoteShipmentPhotoEntity>> =
        container.foreignSiteQuarantine.observeShipmentPhotos().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _quarantineMessage = MutableStateFlow<String?>(null)
    val quarantineMessage: StateFlow<String?> = _quarantineMessage.asStateFlow()

    fun consumeQuarantineMessage() {
        _quarantineMessage.value = null
    }

    /**
     * Экспорт карантина: копий не делаем, отдаём content://-ссылки на
     * оригинальные файлы через FileProvider. Дальше система сама предложит
     * «Сохранить в файлы», почту, мессенджер и т.п.
     *
     * @return Intent для startActivity или null, если экспортировать нечего.
     */
    suspend fun buildQuarantineExportIntent(): Intent? {
        val files = runCatching { container.foreignSiteQuarantine.exportableFiles() }
            .getOrDefault(emptyList())
        if (files.isEmpty()) {
            _quarantineMessage.value = "Файлы карантина не найдены на устройстве"
            return null
        }
        val authority = container.appContext.packageName + ".fileprovider"
        val uris = files.mapNotNull { item ->
            runCatching { FileProvider.getUriForFile(container.appContext, authority, item.file) }.getOrNull()
        }
        if (uris.isEmpty()) {
            _quarantineMessage.value = "Не удалось подготовить файлы к экспорту"
            return null
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "Фото приёмок/отгрузок чужого объекта (${uris.size} шт.)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Экспорт журнала инцидентов. Единственный способ достать его с планшета:
     * logcat инспектору недоступен, Sentry в проде выключен, эндпоинта для
     * клиентских логов на бэкенде нет. Отдаём content://-ссылки на файлы через
     * тот же FileProvider, что и карантин, без копирования.
     *
     * @return Intent для startActivity или null, если журнал пуст.
     */
    suspend fun buildJournalExportIntent(): Intent? {
        val files = runCatching { container.incidentJournal.exportableFiles() }
            .getOrDefault(emptyList())
        if (files.isEmpty()) {
            _quarantineMessage.value = "Журнал пуст — записывать было нечего"
            return null
        }
        val authority = container.appContext.packageName + ".fileprovider"
        val uris = files.mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(container.appContext, authority, file) }.getOrNull()
        }
        if (uris.isEmpty()) {
            _quarantineMessage.value = "Не удалось подготовить журнал к отправке"
            return null
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "Журнал диагностики MatCheck")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun deleteQuarantine() {
        viewModelScope.launch {
            val removed = runCatching { container.foreignSiteQuarantine.deleteAll() }.getOrDefault(0)
            _quarantineMessage.value = "Удалено файлов: $removed"
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            runCatching { container.mutationProcessor.clearAll() }
            runCatching { container.photoUploadProcessor.cleanupBrokenLocalBlobs() }
        }
    }

    fun retryNow() {
        viewModelScope.launch {
            // 1. Чистим «зомби»-мутации с conflictPending от create-on-existing
            // (network-glitch на первом push → 409 при повторе).
            runCatching { container.mutationProcessor.resolveStaleCreateConflicts() }
            // 2. Удаляем фото с потерянными blob'ами — восстановить нечем.
            runCatching { container.photoUploadProcessor.cleanupBrokenLocalBlobs() }
            // 3. Общий push-pull для свежих приёмок/выездов и фото в очереди.
            MatcheckSyncScheduler.requestImmediateSync(container.appContext)
        }
    }

    private companion object {
        val PHOTO_PENDING_STATUSES = listOf("PENDING_UPLOAD", "UPLOADING", "UPLOAD_ERROR")
    }
}
