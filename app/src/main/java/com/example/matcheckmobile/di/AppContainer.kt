package com.example.matcheckmobile.di

import android.content.Context
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.remote.ApiService
import com.example.matcheckmobile.data.remote.MockApiService
import com.example.matcheckmobile.data.repository.AttachmentRepository
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.domain.usecase.CreateOperationUseCase
import com.example.matcheckmobile.media.PhotoStorage

class AppContainer(val appContext: Context) {
    val database: MatcheckDatabase = MatcheckDatabase.get(appContext)

    val apiService: ApiService = MockApiService()

    val deviceSettings: DeviceSettings = DeviceSettings(appContext)

    val photoStorage: PhotoStorage = PhotoStorage(appContext)

    val operationRepository: OperationRepository = OperationRepository(
        operationDao = database.materialOperationDao(),
        attachmentDao = database.operationAttachmentDao(),
        syncQueueDao = database.syncQueueDao(),
    )

    val attachmentRepository: AttachmentRepository = AttachmentRepository(
        attachmentDao = database.operationAttachmentDao(),
    )

    val createOperationUseCase: CreateOperationUseCase = CreateOperationUseCase(operationRepository)
}
