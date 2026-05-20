package com.example.matcheckmobile.di

import android.content.Context
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.remote.ApiService
import com.example.matcheckmobile.data.remote.MockApiService
import com.example.matcheckmobile.data.remote.api.DeliveriesApi
import com.example.matcheckmobile.data.remote.api.PhotosApi
import com.example.matcheckmobile.data.remote.api.ShipmentsApi
import com.example.matcheckmobile.data.remote.api.SourceDocumentsApi
import com.example.matcheckmobile.data.remote.api.SyncApi
import com.example.matcheckmobile.data.remote.net.NetworkFactory
import com.example.matcheckmobile.data.remote.sse.SseConnectionManager
import com.example.matcheckmobile.data.repository.AttachmentRepository
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.data.repository.ConflictRepository
import com.example.matcheckmobile.data.repository.CounterpartyRepository
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.repository.MutationProcessor
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.data.repository.PhotoFetcher
import com.example.matcheckmobile.data.repository.PhotoRepository
import com.example.matcheckmobile.data.repository.PhotoUploadProcessor
import com.example.matcheckmobile.data.repository.ReceiptSessionRepository
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.SourceDocumentRepository
import com.example.matcheckmobile.data.repository.SyncRepository
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.media.LocationProvider
import com.example.matcheckmobile.media.MetadataWatermark
import com.example.matcheckmobile.media.PhotoStorage
import com.example.matcheckmobile.media.RemotePhotoStorage

class AppContainer(val appContext: Context) {
    val database: MatcheckDatabase = MatcheckDatabase.get(appContext)

    val deviceSettings: DeviceSettings = DeviceSettings(appContext)

    val tokenStorage: TokenStorage = TokenStorage(appContext)

    // AuthRepository нужен раньше, чем NetworkFactory отдаёт authApi,
    // потому что NetworkFactory принимает callback "сессия умерла".
    // Решается через lateinit + provider: NetworkFactory зовёт repo.notify,
    // а repo поднимается из NetworkFactory.authApi.
    private lateinit var _authRepository: AuthRepository

    val networkFactory: NetworkFactory = NetworkFactory(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStorage = tokenStorage,
        onSessionInvalidated = { _authRepository.notifySessionInvalidated() },
    )

    val authRepository: AuthRepository = AuthRepository(
        authApi = networkFactory.authApi,
        tokenStorage = tokenStorage,
    ).also { _authRepository = it }

    val syncApi: SyncApi = networkFactory.create(SyncApi::class.java)
    val deliveriesApi: DeliveriesApi = networkFactory.create(DeliveriesApi::class.java)
    val shipmentsApi: ShipmentsApi = networkFactory.create(ShipmentsApi::class.java)
    val sourceDocumentsApi: SourceDocumentsApi = networkFactory.create(SourceDocumentsApi::class.java)
    val photosApi: PhotosApi = networkFactory.create(PhotosApi::class.java)

    val mutationProcessor: MutationProcessor = MutationProcessor(
        mutationDao = database.mutationDao(),
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        deliveriesApi = deliveriesApi,
        shipmentsApi = shipmentsApi,
    )

    val photoUploadProcessor: PhotoUploadProcessor = PhotoUploadProcessor(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        photosApi = photosApi,
    )

    val syncRepository: SyncRepository = SyncRepository(
        syncApi = syncApi,
        deviceSettings = deviceSettings,
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        counterpartyDao = database.remoteCounterpartyDao(),
        materialDao = database.remoteMaterialDao(),
        siteDao = database.remoteSiteDao(),
        statusDao = database.remoteStatusDao(),
        sourceDocumentDao = database.remoteSourceDocumentDao(),
        mutationProcessor = mutationProcessor,
        photoUploadProcessor = photoUploadProcessor,
    )

    val remotePhotoStorage: RemotePhotoStorage = RemotePhotoStorage(appContext)

    val photoRepository: PhotoRepository = PhotoRepository(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        photoStorage = remotePhotoStorage,
    )

    val photoFetcher: PhotoFetcher = PhotoFetcher(photosApi = photosApi)

    val sseConnectionManager: SseConnectionManager = SseConnectionManager(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStorage = tokenStorage,
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        sourceDocumentDao = database.remoteSourceDocumentDao(),
        appContext = appContext,
    )

    val deliveryRepository: DeliveryRepository = DeliveryRepository(
        deliveryDao = database.remoteDeliveryDao(),
        mutationDao = database.mutationDao(),
        localMetaDao = database.deliveryLocalMetaDao(),
    )

    val shipmentRepository: ShipmentRepository = ShipmentRepository(
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
    )

    val conflictRepository: ConflictRepository = ConflictRepository(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
    )

    val apiService: ApiService = MockApiService()

    val photoStorage: PhotoStorage = PhotoStorage(appContext)

    val locationProvider: LocationProvider = LocationProvider(appContext)

    val metadataWatermark: MetadataWatermark = MetadataWatermark()

    val operationRepository: OperationRepository = OperationRepository(
        operationDao = database.materialOperationDao(),
        attachmentDao = database.operationAttachmentDao(),
        syncQueueDao = database.syncQueueDao(),
    )

    val attachmentRepository: AttachmentRepository = AttachmentRepository(
        attachmentDao = database.operationAttachmentDao(),
    )

    val counterpartyRepository: CounterpartyRepository = CounterpartyRepository(
        dao = database.counterpartyDao(),
    )

    val sourceDocumentRepository: SourceDocumentRepository = SourceDocumentRepository(
        dao = database.sourceDocumentDao(),
    )

    val receiptSessionRepository: ReceiptSessionRepository = ReceiptSessionRepository(
        sessionDao = database.receiptSessionDao(),
        operationDao = database.materialOperationDao(),
        attachmentDao = database.operationAttachmentDao(),
        syncQueueDao = database.syncQueueDao(),
    )
}
