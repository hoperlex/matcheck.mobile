package com.example.matcheckmobile

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.MaterialEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.SourceKind
import com.example.matcheckmobile.domain.model.SourceOrigin
import com.example.matcheckmobile.domain.model.SourceStatus
import com.example.matcheckmobile.monitoring.ExitReasonReporter
import com.example.matcheckmobile.monitoring.initSentry
import com.example.matcheckmobile.sync.AppUpdateWorker
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class MatcheckApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // ПЕРВЫМ — до AppContainer, чтобы ловить и ранние падения. No-op без DSN.
        initSentry(this)
        container = AppContainer(this)
        appScope.launch { seedDefaultsIfNeeded() }
        // Незавершённый частичный сброс после смены объекта: процесс мог быть
        // убит между записью долга и чисткой snapshot. Долг лежит в DataStore,
        // доделываем его на старте — не дожидаясь сети и синка. Внутри берётся
        // sync-мьютекс: ниже уже запускается requestImmediateSync, и без
        // барьера сброс и воркер писали бы в одни таблицы одновременно.
        appScope.launch { container.resumeSiteResetExclusively() }
        // Незавершённая очистка данных аккаунта: файлы могли не удалиться
        // (заняты, ошибка ФС) или процесс умер посреди wipe. Room к этому
        // моменту уже пуст — доудаляем остатки jpeg'ов прошлого инспектора.
        appScope.launch { runCatching { container.accountSwitchCoordinator.resumePendingWipe() } }
        // Как завершился прошлый запуск. Читается с IO, а не с Main: обращение
        // к ActivityManager и чтение ANR-трейса — дисковые операции. Нужно для
        // разбора жалоб «приложение пришлось закрыть» (см. ExitReasonReporter).
        appScope.launch {
            runCatching {
                ExitReasonReporter.reportLastExit(
                    context = this@MatcheckApplication,
                    settings = container.deviceSettings,
                    journal = container.incidentJournal,
                )
            }
        }

        // In-app updater: проверяем GH Releases при cold start (один раз,
        // асинхронно, не блокирует UI) + раз в 6 часов через WorkManager,
        // пока приложение живо. В debug-сборке UPDATE_CHECK_ENABLED=false,
        // оба вызова no-op — разработка не отвлекается на проверки.
        container.appUpdateRepository.checkForUpdate()
        AppUpdateWorker.schedule(this)

        if (container.tokenStorage.isAuthenticated()) {
            // Холодный старт с валидной сессией → push-then-pull через
            // WorkManager, плюс периодика на каждые 15 мин.
            MatcheckSyncScheduler.requestImmediateSync(this)
            MatcheckSyncScheduler.schedulePeriodicSync(this)
            // SSE-listener для real-time invalidation. Periodic 15 мин
            // от WorkManager — fallback на случай отключения SSE.
            container.sseConnectionManager.start()
        }

        // ConnectivityManager-callback: при первом появлении сети после
        // оффлайн-сессии мгновенно дёргаем sync, не дожидаясь periodic-15мин
        // или battery-optimization-окна WorkManager на realme/Samsung. На
        // CONNECTED-constraint один WorkManager полагаться нельзя — у этих
        // вендоров OneTimeWork может откладываться на 10–30 минут после
        // возврата сети. Колбэк ставит OneTimeWork-задачу синка сразу, как
        // система отдаёт onAvailable.
        registerNetworkCallbackForAutoSync()
        // На logout/invalid_refresh глобально гасим SSE.
        appScope.launch {
            container.authRepository.sessionEvents.collect { event ->
                if (event is AuthRepository.SessionEvent.LoggedOut) {
                    container.sseConnectionManager.stop()
                }
            }
        }
        // Legacy sync worker отключён: UI приёмок теперь через Stage1/Stage2 →
        // DeliveryRepository.upsert → MutationProcessor (новый push). Старый
        // OperationSyncWorker, если он был запланирован на этом устройстве,
        // снимаем — иначе он пушит остаточные ReceiptSession и сервер
        // создаёт мусорные приёмки со статусом not_filled.
        SyncScheduler.cancelAll(this)
    }

    /**
     * Подписывается на изменения сети и при каждом появлении валидного
     * интернет-соединения (onAvailable) запускает push-then-pull синк.
     *
     * Гарантирует, что данные оффлайн-сессии уходят на сервер **сразу** при
     * возврате сети, не дожидаясь WorkManager-periodic или его CONNECTED-
     * constraint (на realme/Samsung из-за aggressive battery optimization
     * задачи могут откладываться на 10–30 минут). Сам по себе requestImmediate
     * Sync ставит OneTimeWork с REPLACE-политикой — повторные срабатывания
     * не плодят дубли.
     *
     * Колбэк регистрируется на весь жизненный цикл Application — обычное
     * соединение через NetworkRequest INTERNET, без специальных capabilities.
     */
    private fun registerNetworkCallbackForAutoSync() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!container.tokenStorage.isAuthenticated()) return
                MatcheckSyncScheduler.requestImmediateSync(this@MatcheckApplication)
                // Убедиться, что SSE поднят: после потери сети канал мог
                // застрять в backoff-паузе — будим его немедленно, чтобы снова
                // получать real-time события без ожидания следующей попытки.
                container.sseConnectionManager.wake()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private suspend fun seedDefaultsIfNeeded() {
        container.deviceSettings.ensureDeviceId()

        seedSitesIfNeeded()

        // Юзер-сущность теперь приходит из /auth/login (см. LoginViewModel).
        // user-default seed убран намеренно: он маскировал отсутствие реального логина.

        if (container.database.counterpartyDao().count() == 0) {
            seedCounterparties()
        }

        seedMaterialsIfEmpty()

        if (container.database.sourceDocumentDao().count() == 0) {
            seedSourceDocuments()
        }
    }

    private suspend fun seedSitesIfNeeded() {
        val sites = listOf(
            Triple("site-zilart", "ЖК Зиларт", "Москва, Симоновский Вал"),
            Triple("site-king", "ЖК Кинг", "Москва, Кутузовский проспект"),
            Triple("site-stories", "ЖК Сторис", "Москва, Дмитровское шоссе"),
        )
        val now = System.currentTimeMillis()
        val dao = container.database.siteDao()
        for ((id, name, address) in sites) {
            if (dao.findById(id) == null) {
                dao.upsert(
                    SiteEntity(
                        localId = id,
                        serverId = null,
                        name = name,
                        address = address,
                        createdAt = now,
                    )
                )
            }
        }
        val current = container.deviceSettings.currentSiteIdFlow.first()
        if (current.isEmpty() || dao.findById(current) == null) {
            container.deviceSettings.setCurrentSite("site-zilart")
        }
    }

    private suspend fun seedCounterparties() {
        val now = System.currentTimeMillis()
        val list = listOf(
            CounterpartyEntity(
                localId = "cp-stroydom",
                serverId = null,
                inn = "7701234567",
                kpp = "770101001",
                name = "ООО «СтройДом»",
                address = "Москва, ул. Стройная, 12",
                isSupplier = true,
                isCustomer = false,
                isCarrier = false,
                isContractor = false,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-metallinvest",
                serverId = null,
                inn = "7707654321",
                kpp = "770701001",
                name = "ООО «МеталлИнвест»",
                address = "Москва, проспект Мира, 88",
                isSupplier = true,
                isCustomer = false,
                isCarrier = false,
                isContractor = false,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-cementgroup",
                serverId = null,
                inn = "5012345678",
                kpp = "501201001",
                name = "ООО «ЦементГрупп»",
                address = "МО, Подольск, ул. Заводская, 5",
                isSupplier = true,
                isCustomer = false,
                isCarrier = false,
                isContractor = false,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-pesokopt",
                serverId = null,
                inn = "5099887766",
                kpp = "509901001",
                name = "ИП Иванов А. С. (ПесокОпт)",
                address = "МО, Раменское, карьер Северный",
                isSupplier = true,
                isCustomer = false,
                isCarrier = false,
                isContractor = false,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-derevohouse",
                serverId = null,
                inn = "7811223344",
                kpp = "781101001",
                name = "ООО «ДеревоХаус»",
                address = "Санкт-Петербург, ул. Лесная, 24",
                isSupplier = true,
                isCustomer = false,
                isCarrier = false,
                isContractor = false,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-monolitstroy",
                serverId = null,
                inn = "7712345601",
                kpp = "771201001",
                name = "ООО «МонолитСтрой»",
                address = "Москва, ул. Подрядная, 7",
                isSupplier = false,
                isCustomer = false,
                isCarrier = false,
                isContractor = true,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-fasadprof",
                serverId = null,
                inn = "7728765432",
                kpp = "772801001",
                name = "ООО «ФасадПроф»",
                address = "Москва, ул. Профильная, 14",
                isSupplier = false,
                isCustomer = false,
                isCarrier = false,
                isContractor = true,
                updatedAt = now,
            ),
            CounterpartyEntity(
                localId = "cp-elektrosila",
                serverId = null,
                inn = "7743210987",
                kpp = "774301001",
                name = "ООО «ЭлектроСила»",
                address = "Москва, ул. Электриков, 5",
                isSupplier = false,
                isCustomer = false,
                isCarrier = false,
                isContractor = true,
                updatedAt = now,
            ),
        )
        for (cp in list) container.database.counterpartyDao().upsert(cp)
    }

    private suspend fun seedMaterialsIfEmpty() {
        // MaterialDao не имеет count(), пробуем добавить — REPLACE безопасен
        val now = System.currentTimeMillis()
        val materials = listOf(
            MaterialEntity(localId = "mat-cement-m400", serverId = null, code = "CEM400", name = "Цемент М400", unit = "т", updatedAt = now),
            MaterialEntity(localId = "mat-cement-m500", serverId = null, code = "CEM500", name = "Цемент М500", unit = "т", updatedAt = now),
            MaterialEntity(localId = "mat-sand", serverId = null, code = "SAND", name = "Песок строительный", unit = "м³", updatedAt = now),
            MaterialEntity(localId = "mat-shchebenj", serverId = null, code = "GRVL", name = "Щебень фракция 20-40", unit = "м³", updatedAt = now),
            MaterialEntity(localId = "mat-kirpich", serverId = null, code = "BRCK", name = "Кирпич облицовочный", unit = "шт", updatedAt = now),
            MaterialEntity(localId = "mat-armatura", serverId = null, code = "REBR12", name = "Арматура Ø12 мм", unit = "т", updatedAt = now),
            MaterialEntity(localId = "mat-boards", serverId = null, code = "BRD25", name = "Доска обрезная 25×150", unit = "м³", updatedAt = now),
        )
        container.database.materialDao().upsertAll(materials)
    }

    private suspend fun seedSourceDocuments() {
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000

        suspend fun makeDoc(
            id: String,
            number: String,
            supplierId: String,
            daysAgo: Int,
            total: Double,
            items: List<Triple<String, Double, String>>, // nameRaw, qty, unit
        ) {
            val doc = SourceDocumentEntity(
                localId = id,
                serverId = null,
                kind = SourceKind.UPD,
                status = SourceStatus.PARSED,
                supplierId = supplierId,
                recipientId = null,
                docNumber = number,
                docDate = now - daysAgo * day,
                totalSum = total,
                vatSum = total * 0.2 / 1.2,
                expectedDate = now + (1 - daysAgo) * day,
                origin = SourceOrigin.EDO_DIADOC,
                updatedAt = now,
            )
            container.database.sourceDocumentDao().upsertDocument(doc)
            val rows = items.mapIndexed { idx, (name, qty, unit) ->
                SourceDocumentItemEntity(
                    localId = UUID.randomUUID().toString(),
                    sourceDocumentLocalId = id,
                    materialId = null,
                    nameRaw = name,
                    qty = qty,
                    unit = unit,
                    price = null,
                    sum = null,
                    lineNo = idx + 1,
                )
            }
            container.database.sourceDocumentDao().upsertItems(rows)
        }

        makeDoc(
            id = "doc-upd-001",
            number = "УПД-2026-014",
            supplierId = "cp-cementgroup",
            daysAgo = 1,
            total = 180_000.0,
            items = listOf(
                Triple("Цемент М400", 10.0, "т"),
                Triple("Цемент М500", 5.0, "т"),
            ),
        )
        makeDoc(
            id = "doc-upd-002",
            number = "УПД-2026-027",
            supplierId = "cp-pesokopt",
            daysAgo = 0,
            total = 92_500.0,
            items = listOf(
                Triple("Песок строительный", 50.0, "м³"),
                Triple("Щебень фракция 20-40", 30.0, "м³"),
            ),
        )
        makeDoc(
            id = "doc-upd-003",
            number = "УПД-2026-031",
            supplierId = "cp-metallinvest",
            daysAgo = 2,
            total = 245_000.0,
            items = listOf(
                Triple("Арматура Ø12 мм", 4.5, "т"),
                Triple("Арматура Ø16 мм", 2.0, "т"),
            ),
        )
        makeDoc(
            id = "doc-upd-004",
            number = "УПД-2026-040",
            supplierId = "cp-derevohouse",
            daysAgo = 3,
            total = 67_800.0,
            items = listOf(
                Triple("Доска обрезная 25×150", 8.0, "м³"),
                Triple("Брус 150×150", 4.0, "м³"),
            ),
        )
    }
}
