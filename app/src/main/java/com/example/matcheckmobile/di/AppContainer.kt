package com.example.matcheckmobile.di

import android.content.Context
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.data.auth.AccountSwitchCoordinator
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
import com.example.matcheckmobile.data.repository.ForeignSiteQuarantine
import com.example.matcheckmobile.data.repository.ManualDispatchDraftRepository
import com.example.matcheckmobile.data.repository.ManualEntryDraftRepository
import com.example.matcheckmobile.data.repository.ShipmentStage1DraftRepository
import com.example.matcheckmobile.data.repository.ShipmentStage2DraftRepository
import com.example.matcheckmobile.data.repository.Stage1DraftRepository
import com.example.matcheckmobile.data.repository.Stage2DraftRepository
import com.example.matcheckmobile.data.repository.MutationProcessor
import com.example.matcheckmobile.data.repository.PendingAwareRemoteWriter
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.data.repository.PhotoFetcher
import com.example.matcheckmobile.data.repository.PhotoRepository
import com.example.matcheckmobile.data.repository.PhotoUploadProcessor
import com.example.matcheckmobile.data.repository.SourceDocumentBackfillService
import com.example.matcheckmobile.data.repository.ReceiptSessionRepository
import com.example.matcheckmobile.data.repository.ShipmentRepository
import com.example.matcheckmobile.data.repository.SiteChangeReset
import com.example.matcheckmobile.data.repository.SourceDocumentRepository
import com.example.matcheckmobile.data.repository.SyncRepository
import com.example.matcheckmobile.data.repository.RoomTransactionRunner
import com.example.matcheckmobile.data.repository.TerminalConflictResolver
import com.example.matcheckmobile.data.repository.TransactionRunner
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.monitoring.IncidentJournal
import com.example.matcheckmobile.presentation.components.FinalizeFeedbackController
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.media.LocationProvider
import com.example.matcheckmobile.media.MetadataWatermark
import com.example.matcheckmobile.media.PhotoStorage
import com.example.matcheckmobile.media.RemotePhotoStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(val appContext: Context) {
    val database: MatcheckDatabase = MatcheckDatabase.get(appContext)

    /**
     * Application-scoped IO-корутины: живут дольше любого ViewModel. Нужны для
     * работ, которые обязаны завершиться даже после того, как экран/VM убит
     * (напр. дозапись/очистка черновика «Ручного внеса» на выходе с формы —
     * viewModelScope там уже отменён popBackStack'ом).
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Объявлен до mutationProcessor/terminalConflictResolver — оба его требуют,
    // а инициализация свойств идёт по порядку объявления.
    private val transactionRunner: TransactionRunner = RoomTransactionRunner(database)

    /** Карантин записей чужого объекта (403 foreign_site). Требуется обоим процессорам. */
    val foreignSiteQuarantine: ForeignSiteQuarantine = ForeignSiteQuarantine(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        tx = transactionRunner,
    )

    /**
     * Единственный путь записи серверных агрегатов приёмок и отгрузок.
     * Объявлен до mutationProcessor и syncRepository — оба его требуют.
     */
    val remoteWriter: PendingAwareRemoteWriter = PendingAwareRemoteWriter(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        tx = transactionRunner,
    )

    val mutationProcessor: MutationProcessor = MutationProcessor(
        mutationDao = database.mutationDao(),
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        deliveriesApi = deliveriesApi,
        shipmentsApi = shipmentsApi,
        remoteWriter = remoteWriter,
        quarantine = foreignSiteQuarantine,
    )

    val photoUploadProcessor: PhotoUploadProcessor = PhotoUploadProcessor(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        photosApi = photosApi,
        quarantine = foreignSiteQuarantine,
    )

    /**
     * Частичный сброс snapshot при смене объекта. Объявлен ДО syncRepository:
     * его дёргают колбэки onAfterPullRefresh / onAfterPhotoUpload.
     */
    val siteChangeReset: SiteChangeReset = SiteChangeReset(
        deviceSettings = deviceSettings,
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        sourceDocumentDao = database.remoteSourceDocumentDao(),
        mutationDao = database.mutationDao(),
        quarantine = foreignSiteQuarantine,
        tx = transactionRunner,
        tokenSiteId = { tokenStorage.state.value.siteId },
        persistTokenSiteId = { tokenStorage.updateSiteId(it) },
        // syncRepository объявлен ниже — обращаемся лениво, к моменту вызова
        // контейнер уже собран.
        onResetDone = { syncRepository.resetReconcileThrottle() },
    )

    val terminalConflictResolver: TerminalConflictResolver = TerminalConflictResolver(
        deliveryDao = database.remoteDeliveryDao(),
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        tx = transactionRunner,
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
        remoteWriter = remoteWriter,
        mutationProcessor = mutationProcessor,
        photoUploadProcessor = photoUploadProcessor,
        terminalConflictResolver = terminalConflictResolver,
        // Сразу после pull подтягиваем актуальный user.siteId (штамп фото и
        // фильтры списков зависят от него). Тяжёлый side-effect смены объекта —
        // сброс server-snapshot — отложен до onAfterPhotoUpload, чтобы не
        // снести незалитые фото. Все ошибки проглатываются внутри.
        onAfterPullRefresh = ::refreshSiteIdAfterPull,
        onAfterPhotoUpload = ::applySiteChangeAfterPhotos,
        // Долг на сброс переживает перезапуск процесса — подхватываем его
        // в начале каждого цикла, а не только сразу после смены объекта.
        onBeforeSync = { runCatching { siteChangeReset.resumeIfNeeded() } },
        currentSiteId = { tokenStorage.state.value.effectiveSiteId },
    )

    /**
     * Side-effect сразу после успешного pull: подтянуть актуальный user.siteId.
     * Дешёвая операция (/auth/me + запись в TokenStorage), от которой зависят
     * штамп объекта на фото 1 Этапа и siteId-фильтры списков — поэтому она
     * выполняется ДО загрузки фото и не страдает от её сбоев.
     *
     * Порядок шагов критичен и обратен интуитивному:
     *
     * 1. читаем новый siteId с сервера, НЕ трогая TokenStorage;
     * 2. пишем персистентный долг на сброс snapshot;
     * 3. и только теперь сохраняем siteId в токене.
     *
     * Если шаг 2 упал — токен не меняем вовсе: пусть лучше приложение ещё
     * поработает на старом объекте и повторит попытку на следующем синке.
     * Если процесс умрёт между 2 и 3, долг останется, и SiteChangeReset
     * досохранит siteId сам. Обратный порядок (как было раньше) давал
     * необратимую потерю: siteId сохранён, долга нет, следующий /me не видит
     * изменений — планшет навсегда со снимком прошлого объекта.
     */
    private suspend fun refreshSiteIdAfterPull() {
        val fetched = authRepository.fetchServerSiteId() ?: return
        val marked = runCatching { siteChangeReset.markPending(fetched) }.isSuccess
        if (!marked) return
        tokenStorage.updateSiteId(fetched)
    }

    /**
     * Тяжёлое последствие смены объекта: server-snapshot УПД/приёмок/отгрузок
     * относится к старому объекту и должен быть перечитан под новый siteId.
     * Чистим snapshot и дёргаем requestImmediateSync — WorkManager поставит
     * новый job, он подтянет данные нового объекта.
     *
     * Делается ПОСЛЕ загрузки фото: даём незалитым снимкам шанс уйти на сервер
     * до чистки. Те, что не ушли, сброс всё равно не тронет — записи с
     * неотправленными фото, карантином или непустой очередью он пропускает
     * (см. [SiteChangeReset]).
     *
     * Локальные мутации, черновики, токены и общие справочники
     * (sites/statuses/counterparties/materials) НЕ ТРОГАЕМ.
     */
    private suspend fun applySiteChangeAfterPhotos() {
        val done = runCatching { siteChangeReset.resumeIfNeeded() }.getOrDefault(false)
        if (done) MatcheckSyncScheduler.requestImmediateSync(appContext)
    }

    /**
     * Догоняющий сброс со старта приложения — процесс мог быть убит между
     * записью долга и чисткой snapshot.
     *
     * ОБЯЗАТЕЛЬНО под sync-мьютексом: без него сброс идёт параллельно воркеру,
     * который на старте уже запущен через requestImmediateSync, и они топчутся
     * по одним и тем же таблицам. Из onBeforeSync мьютекс уже взят — там
     * вызывается resumeIfNeeded напрямую, иначе был бы deadlock.
     */
    suspend fun resumeSiteResetExclusively() {
        val done = runCatching {
            syncRepository.runExclusively { siteChangeReset.resumeIfNeeded() }
        }.getOrDefault(false)
        if (done) MatcheckSyncScheduler.requestImmediateSync(appContext)
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

    /**
     * Смена аккаунта на устройстве: гасит SSE, снимает sync-задачи, дожидается
     * текущего syncOnce и чистит Room до активации новой сессии. Создаётся
     * после syncRepository/sseConnectionManager — зависит от обоих.
     */
    val accountSwitchCoordinator: AccountSwitchCoordinator = AccountSwitchCoordinator(
        appContext = appContext,
        database = database,
        deviceSettings = deviceSettings,
        syncRepository = syncRepository,
        sseConnectionManager = sseConnectionManager,
    )

    val deliveryRepository: DeliveryRepository = DeliveryRepository(
        deliveryDao = database.remoteDeliveryDao(),
        mutationDao = database.mutationDao(),
        localMetaDao = database.deliveryLocalMetaDao(),
        tx = transactionRunner,
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

    val manualEntryDraftRepository: ManualEntryDraftRepository =
        ManualEntryDraftRepository(dao = database.manualEntryDraftDao())

    val manualDispatchDraftRepository: ManualDispatchDraftRepository =
        ManualDispatchDraftRepository(dao = database.manualDispatchDraftDao())

    val sourceDocumentBackfillService: SourceDocumentBackfillService = SourceDocumentBackfillService(
        api = sourceDocumentsApi,
        dao = database.remoteSourceDocumentDao(),
    )

    val shipmentRepository: ShipmentRepository = ShipmentRepository(
        shipmentDao = database.remoteShipmentDao(),
        mutationDao = database.mutationDao(),
        localMetaDao = database.shipmentLocalMetaDao(),
        tx = transactionRunner,
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

    /** Диагностический журнал на диске: заполняется финализацией и стартом. */
    val incidentJournal: IncidentJournal = IncidentJournal(appContext, appScope)

    /**
     * Отдельный scope под таймер success-оверлея: Default, а не IO, потому что
     * работа там — один delay, и путать её с файловым/сетевым пулом незачем.
     * Главное — он не Main: отсчёт не должен зависеть от занятости UI-потока.
     */
    private val feedbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val finalizeFeedback: FinalizeFeedbackController =
        FinalizeFeedbackController(feedbackScope, incidentJournal)
}
