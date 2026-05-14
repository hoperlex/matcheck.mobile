package com.example.matcheckmobile

import android.app.Application
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.MaterialEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import com.example.matcheckmobile.data.local.entity.UserEntity
import com.example.matcheckmobile.di.AppContainer
import com.example.matcheckmobile.domain.model.SourceKind
import com.example.matcheckmobile.domain.model.SourceOrigin
import com.example.matcheckmobile.domain.model.SourceStatus
import com.example.matcheckmobile.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class MatcheckApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { seedDefaultsIfNeeded() }
        SyncScheduler.schedulePeriodicSync(this)
    }

    private suspend fun seedDefaultsIfNeeded() {
        container.deviceSettings.ensureDeviceId()

        val siteId = "site-default"
        if (container.database.siteDao().findById(siteId) == null) {
            container.database.siteDao().upsert(
                SiteEntity(
                    localId = siteId,
                    serverId = null,
                    name = "Объект по умолчанию",
                    address = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
            container.deviceSettings.setCurrentSite(siteId)
        }

        val userId = "user-default"
        if (container.database.userDao().findById(userId) == null) {
            container.database.userDao().upsert(
                UserEntity(
                    localId = userId,
                    serverId = null,
                    fullName = "Охранник КПП",
                    email = null,
                    role = "inspector_kpp",
                    isActive = true,
                    createdAt = System.currentTimeMillis(),
                )
            )
            container.deviceSettings.setCurrentUser(userId)
        }

        if (container.database.counterpartyDao().count() == 0) {
            seedCounterparties()
        }

        seedMaterialsIfEmpty()

        if (container.database.sourceDocumentDao().count() == 0) {
            seedSourceDocuments()
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
