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
import com.example.matcheckmobile.data.remote.update.AppUpdateFetcher
import com.example.matcheckmobile.data.repository.AppUpdateDownloader
import com.example.matcheckmobile.data.repository.AppUpdateInstaller
import com.example.matcheckmobile.data.repository.AppUpdateRepository
import com.example.matcheckmobile.data.repository.AttachmentRepository
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.data.repository.ConflictRepository
import com.example.matcheckmobile.data.repository.CounterpartyRepository
import com.example.matcheckmobile.data.repository.DeliveryRepository
import com.example.matcheckmobile.data.repository.ShipmentStage1DraftRepository
import com.example.matcheckmobile.data.repository.ShipmentStage2DraftRepository
import com.example.matcheckmobile.data.repository.Stage1DraftRepository
import com.example.matcheckmobile.data.repository.Stage2DraftRepository
import com.example.matcheckmobile.data.repository.MutationProcessor
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.data.repository.PhotoFetcher
import com.example.matcheckmobile.data.repository.PhotoRepository
import com.example.matcheckmobile.data.repository.PhotoUploadProcessor
import com.example.matcheckmobile.data.repository.SourceDocumentBackfillService
import com.example.matcheckmobile.data.repository.ReceiptSessionRepository
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.SourceDocumentRepository
import com.example.matcheckmobile.data.repository.SyncRepository
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.media.LocationProvider
import com.example.matcheckmobile.media.MetadataWatermark
import com.example.matcheckmobile.media.PhotoStorage
import com.example.matcheckmobile.media.RemotePhotoStorage

class AppContainer(val appContext: Context) {
    val database: MatcheckDatabase = MatcheckDatabase.get(appContext)

    val deviceSettings: DeviceSettings = DeviceSettings(appContext)

    val tokenStorage: TokenStorage = TokenStorage(appContext)

    val logoutAuditLog: com.example.matcheckmobile.data.auth.LogoutAuditLog =
        com.example.matcheckmobile.data.auth.LogoutAuditLog(appContext)

    // Запомненные логин+пароль для автозаполнения экрана входа после logout.
    // Отдельно от tokenStorage — переживает logout (см. класс).
    val rememberedCredentialsStore: com.example.matcheckmobile.data.auth.RememberedCredentialsStore =
        com.example.matcheckmobile.data.auth.RememberedCredentialsStore(appContext)

    // AuthRepository нужен раньше, чем NetworkFactory отдаёт authApi,
    // потому что NetworkFactory принимает callback "сессия умерла".
    // Решается через lateinit + provider: NetworkFactory зовёт repo.notify,
    // а repo поднимается из NetworkFactory.authApi.
    private lateinit var _authRepository: AuthRepository

    val networkFactory: NetworkFactory = NetworkFactory(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStorage = tokenStorage,
        onSessionInvalidated = { path, code -> _authRepository.notifySessionInvalidated(path, code) },
    )

    val authRepository: AuthRepository = AuthRepository(
        authApi = networkFactory.authApi,
        tokenStorage = tokenStorage,
        logoutAuditLog = logoutAuditLog,
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
        deliveriesApi = deliveriesApi,
        shipmentsApi = shipmentsApi,
        sourceDocumentsApi = sourceDocumentsApi,
        deviceSettings = deviceSettings,
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        counterpartyDao = database.remoteCounterpartyDao(),
        materialDao = database.remoteMaterialDao(),
        siteDao = database.remoteSiteDao(),
        statusDao = database.remoteStatusDao(),
        unitDao = database.remoteUnitDao(),
        sourceDocumentDao = database.remoteSourceDocumentDao(),
        mutationDao = database.mutationDao(),
        mutationProcessor = mutationProcessor,
        photoUploadProcessor = photoUploadProcessor,
        // После успешного pull тихо подтягиваем актуальный user.siteId.
        // Если siteId реально изменился — чистим server-snapshot (УПД/
        // приёмки/отгрузки) и планируем новый sync под новый siteId
        // (см. handleSiteIdRefresh). Все ошибки проглатываются внутри —
        // sync основного цикла не пострадает.
        onAfterPullRefresh = ::handleSiteIdRefresh,
    )

    /**
     * Side-effect после успешного syncOnce: подтянуть актуальный
     * user.siteId. Если значение изменилось — server-snapshot УПД/
     * приёмок/отгрузок относится к старому объекту и должен быть
     * перечитан под новый siteId. Чистим snapshot, дёргаем
     * MatcheckSyncScheduler.requestImmediateSync — WorkManager поставит
     * новый job в очередь, он отработает после завершения текущего
     * syncOnce (REPLACE policy) и подтянет данные нового объекта.
     *
     * Локальные мутации, черновики, фото, токены и общие справочники
     * (sites/statuses/counterparties/materials) НЕ ТРОГАЕМ —
     * см. SyncRepository.resetServerSnapshotOnSiteChange комментарий.
     */
    private suspend fun handleSiteIdRefresh() {
        val changed = authRepository.refreshSiteIdFromServer()
        if (!changed) return
        syncRepository.resetServerSnapshotOnSiteChange()
        MatcheckSyncScheduler.requestImmediateSync(appContext)
    }

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

    val stage1DraftRepository: Stage1DraftRepository = Stage1DraftRepository(
        dao = database.stage1DraftDao(),
    )

    val stage2DraftRepository: Stage2DraftRepository = Stage2DraftRepository(
        dao = database.stage2DraftDao(),
    )

    val shipmentStage1DraftRepository: ShipmentStage1DraftRepository =
        ShipmentStage1DraftRepository(dao = database.shipmentStage1DraftDao())

    val shipmentStage2DraftRepository: ShipmentStage2DraftRepository =
        ShipmentStage2DraftRepository(dao = database.shipmentStage2DraftDao())

    val sourceDocumentBackfillService: SourceDocumentBackfillService = SourceDocumentBackfillService(
        api = sourceDocumentsApi,
        dao = database.remoteSourceDocumentDao(),
    )

    val shipmentRepository: ShipmentRepository = ShipmentRepository(
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        localMetaDao = database.shipmentLocalMetaDao(),
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

    // In-app updater. URL манифеста зашит в BuildConfig per build type:
    // в release указывает на GH Releases, в debug — пустая строка
    // (fetcher вернёт Failure → AppUpdateRepository никогда не покажет
    // диалог в dev-сборке).
    val appUpdateRepository: AppUpdateRepository = AppUpdateRepository(
        appContext = appContext,
        fetcher = AppUpdateFetcher(manifestUrl = BuildConfig.UPDATE_MANIFEST_URL),
        downloader = AppUpdateDownloader(appContext = appContext),
        installer = AppUpdateInstaller(),
    )
}
