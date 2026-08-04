package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.mapper.RemoteMappers.toEntity
import com.example.matcheckmobile.data.remote.api.DeliveriesApi
import com.example.matcheckmobile.data.remote.api.ShipmentsApi
import com.example.matcheckmobile.data.remote.api.dto.DeliveryDto
import com.example.matcheckmobile.data.remote.api.dto.DeliveryListResponse
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.MarkDeletionRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentDto
import com.example.matcheckmobile.data.remote.api.dto.ShipmentListResponse
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.StatusDto
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

/**
 * Поведение [MutationProcessor] на 409-конфликтах приёмок/отгрузок: terminal
 * server-win строго для delivery **upsert**; idempotent-create; заморозка
 * нетерминальных и не-upsert конфликтов; межзапусковый FIFO (pre-block).
 * in-memory Room + fake retrofit-API. ЗАПУСК — на устройстве/эмуляторе (или CI).
 */
@RunWith(AndroidJUnit4::class)
class MutationProcessorConflictTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveryDao: RemoteDeliveryDao
    private lateinit var shipmentDao: RemoteShipmentDao
    private lateinit var mutationDao: MutationDao
    private lateinit var deliveriesApi: FakeDeliveriesApi
    private lateinit var shipmentsApi: FakeShipmentsApi
    private lateinit var processor: MutationProcessor

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val ts = "2026-07-24T00:00:00Z"

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java).allowMainThreadQueries().build()
        deliveryDao = db.remoteDeliveryDao()
        shipmentDao = db.remoteShipmentDao()
        mutationDao = db.mutationDao()
        deliveriesApi = FakeDeliveriesApi()
        shipmentsApi = FakeShipmentsApi()
        processor = MutationProcessor(
            mutationDao,
            deliveryDao,
            shipmentDao,
            deliveriesApi,
            shipmentsApi,
            PendingAwareRemoteWriter(deliveryDao, shipmentDao, mutationDao, RoomTransactionRunner(db)),
            ForeignSiteQuarantine(deliveryDao, shipmentDao, mutationDao, RoomTransactionRunner(db)),
            DiscardTelemetry.noop,
        )
    }

    @After
    fun teardown() = db.close()

    // --- fixtures ---

    private fun deliveryUpsertPayload(id: String, baseVersion: Int?): String = json.encodeToString(
        DeliveryUpsertRequest.serializer(),
        DeliveryUpsertRequest(id = id, statusCode = "confirmed_mol", siteId = "site-1", baseVersion = baseVersion),
    )

    private fun shipmentUpsertPayload(id: String, baseVersion: Int?): String = json.encodeToString(
        ShipmentUpsertRequest.serializer(),
        ShipmentUpsertRequest(id = id, statusCode = "confirmed_mol", kind = "contractor", siteId = "site-1", baseVersion = baseVersion),
    )

    private fun mut(
        mid: String,
        entityId: String,
        entityType: String = "delivery",
        op: String = "upsert",
        baseVersion: Int? = 1,
        conflict: Boolean = false,
        payload: String? = null,
        createdAt: Long = 1L,
    ) = MutationEntity(
        id = mid, entityType = entityType, operation = op, entityId = entityId,
        baseVersion = baseVersion, payloadJson = payload, attempts = if (conflict) 1 else 0,
        nextAttemptAt = null, lastError = if (conflict) "conflict" else null,
        conflictPending = conflict, createdAt = createdAt,
    )

    private fun deliveryConflict409(code: String, serverVersion: Int, id: String): HttpException {
        val server = DeliveryDto(id = id, status = StatusDto("st", "delivery", code, code, sortOrder = 40), siteId = "site-1", version = serverVersion, createdAt = ts, updatedAt = ts)
        val serverJson = json.encodeToString(DeliveryDto.serializer(), server)
        val body = """{"error":"conflict","serverVersion":$serverVersion,"server":$serverJson}"""
        return HttpException(Response.error<Any>(409, body.toResponseBody("application/json".toMediaType())))
    }

    private fun shipmentConflict409(code: String, serverVersion: Int, id: String): HttpException {
        val server = ShipmentDto(id = id, status = StatusDto("st", "shipment", code, code, sortOrder = 40), kind = "contractor", siteId = "site-1", version = serverVersion, createdAt = ts, updatedAt = ts)
        val serverJson = json.encodeToString(ShipmentDto.serializer(), server)
        val body = """{"error":"conflict","serverVersion":$serverVersion,"server":$serverJson}"""
        return HttpException(Response.error<Any>(409, body.toResponseBody("application/json".toMediaType())))
    }

    // --- tests ---

    @Test
    fun terminalUpsert409_serverWin_mutationDeleted_entityConfirmed() = runBlocking {
        val id = "d1"
        mutationDao.upsert(mut("m1", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1)))
        deliveriesApi.onUpsert = { throw deliveryConflict409("confirmed_mol", 2, id) }

        processor.processAll()

        assertTrue("конфликтная мутация удалена (server-win)", mutationDao.findFor("delivery", id).isEmpty())
        val row = deliveryDao.findById(id)!!
        assertEquals("confirmed_mol", row.statusCode)
        assertFalse(row.conflictPending)
    }

    @Test
    fun idempotentCreate_serverFilled_ok_noFreeze() = runBlocking {
        val id = "d2"
        mutationDao.upsert(mut("m2", id, baseVersion = 0, payload = deliveryUpsertPayload(id, 0)))
        deliveriesApi.onUpsert = { throw deliveryConflict409("filled", 1, id) }

        processor.processAll()

        assertTrue("idempotent-create → Ok, мутация удалена", mutationDao.findFor("delivery", id).isEmpty())
        assertFalse(deliveryDao.findById(id)!!.conflictPending)
    }

    @Test
    fun idempotentCreate_serverConfirmed_ok_notDiscardPath() = runBlocking {
        val id = "d3"
        mutationDao.upsert(mut("m3", id, baseVersion = 0, payload = deliveryUpsertPayload(id, 0)))
        deliveriesApi.onUpsert = { throw deliveryConflict409("confirmed_mol", 2, id) }

        processor.processAll()

        // idempotent имеет приоритет над terminal — тоже Ok, без «discard»-семантики.
        assertTrue(mutationDao.findFor("delivery", id).isEmpty())
        val row = deliveryDao.findById(id)!!
        assertEquals("confirmed_mol", row.statusCode)
        assertFalse(row.conflictPending)
    }

    @Test
    fun nonTerminalUpsert409_frozen() = runBlocking {
        val id = "d4"
        mutationDao.upsert(mut("m4", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1)))
        deliveriesApi.onUpsert = { throw deliveryConflict409("filled", 2, id) }

        processor.processAll()

        val muts = mutationDao.findFor("delivery", id)
        assertEquals(1, muts.size)
        assertTrue("нетерминал → заморожен", muts.first().conflictPending)
        assertTrue(deliveryDao.findById(id)!!.conflictPending)
    }

    @Test
    fun terminalMarkDeletion409_frozen_notAutoResolved() = runBlocking {
        val id = "d5"
        // payload=null → dispatch использует MarkDeletionRequest() по умолчанию
        mutationDao.upsert(mut("m5", id, op = "mark_deletion", baseVersion = 1, payload = null))
        deliveriesApi.onMarkDeletion = { throw deliveryConflict409("confirmed_mol", 2, id) }

        processor.processAll()

        val muts = mutationDao.findFor("delivery", id)
        assertEquals("mark_deletion при терминале НЕ авто-резолвится", 1, muts.size)
        assertTrue(muts.first().conflictPending)
    }

    @Test
    fun shipmentTerminal409_frozen_scopeUnchanged() = runBlocking {
        val id = "s1"
        mutationDao.upsert(mut("ms1", id, entityType = "shipment", baseVersion = 1, payload = shipmentUpsertPayload(id, 1)))
        shipmentsApi.onUpsert = { throw shipmentConflict409("confirmed_mol", 2, id) }

        processor.processAll()

        val muts = mutationDao.findFor("shipment", id)
        assertEquals("отгрузки не трогаем — терминал замораживается как раньше", 1, muts.size)
        assertTrue(muts.first().conflictPending)
        assertTrue(shipmentDao.findById(id)!!.conflictPending)
    }

    @Test
    fun fifo_preBlock_laterMutationNotRunAheadOfConflict() = runBlocking {
        val id = "d6"
        // m1 — уже конфликтная (исключена из listPending); m2 — поздняя pending той же приёмки.
        mutationDao.upsert(mut("m1", id, baseVersion = 1, conflict = true, payload = deliveryUpsertPayload(id, 1), createdAt = 1L))
        mutationDao.upsert(mut("m2", id, baseVersion = 1, conflict = false, payload = deliveryUpsertPayload(id, 1), createdAt = 2L))

        processor.processAll()

        assertTrue("m2 НЕ отправлена (pre-block по listConflicts)", deliveriesApi.upsertCalls.isEmpty())
        val muts = mutationDao.findFor("delivery", id).associateBy { it.id }
        assertEquals("обе мутации на месте", 2, muts.size)
        assertTrue("m1 остаётся конфликтной", muts.getValue("m1").conflictPending)
        assertFalse("m2 не осиротела и не выполнена", muts.getValue("m2").conflictPending)
    }

    @Test
    fun foreignSite403_dropsQueueButQuarantinesUnsentPhotos() = runBlocking {
        val id = "d7"
        val blob = File.createTempFile("blob", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        val thumb = File.createTempFile("thumb", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        seedDelivery(id, blob = blob, thumb = thumb)
        // Две мутации той же приёмки: обе должны исчезнуть, вторая — не уйти на сервер.
        mutationDao.upsert(mut("m7a", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1), createdAt = 1L))
        mutationDao.upsert(mut("m7b", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1), createdAt = 2L))
        deliveriesApi.onUpsert = { throw foreignSite403() }

        processor.processAll()

        assertTrue("вся очередь сущности вычищена", mutationDao.findFor("delivery", id).isEmpty())
        assertEquals("вторая мутация не отправлялась (entity заблокирована)", 1, deliveriesApi.upsertCalls.size)
        // Требование «без потерь»: снимок не отправится никогда, но и не пропадёт —
        // терминальный карантин, файлы на месте, решение за человеком.
        assertNotNull("запись оставлена контейнером для снимка", deliveryDao.findById(id))
        assertEquals(
            RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
            deliveryDao.findPhotoById("p-$id")!!.uploadStatus,
        )
        assertTrue("blob-файл сохранён", blob.exists())
        assertTrue("thumb-файл сохранён", thumb.exists())
        assertTrue(
            "карантин не попадает в upload-цикл",
            deliveryDao.findPhotosByStatus(RemotePhotoStatus.UPLOADABLE).isEmpty(),
        )
    }

    @Test
    fun foreignSite403_deletesEntity_whenNothingToSalvage() = runBlocking {
        val id = "d8"
        val dto = DeliveryDto(
            id = id,
            status = StatusDto("st", "delivery", "confirmed_mol", "confirmed_mol", sortOrder = 40),
            siteId = "site-foreign",
            version = 1,
            createdAt = ts,
            updatedAt = ts,
        )
        deliveryDao.saveAggregate(delivery = dto.toEntity(), items = emptyList(), photos = emptyList())
        mutationDao.upsert(mut("m8", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1)))
        deliveriesApi.onUpsert = { throw foreignSite403() }

        processor.processAll()

        assertNull("фото нет — держать запись незачем", deliveryDao.findById(id))
        assertTrue(mutationDao.findFor("delivery", id).isEmpty())
    }

    @Test
    fun legacyForeignSiteFailure_isSweptOnFirstRun() = runBlocking {
        // Состояние, которое мог записать клиент 1.0.29: он не знал кода
        // foreign_site и заморозил мутацию как конфликтную с сырым телом.
        val id = "d9"
        val dto = DeliveryDto(
            id = id,
            status = StatusDto("st", "delivery", "filled", "filled", sortOrder = 20),
            siteId = "site-foreign",
            version = 1,
            createdAt = ts,
            updatedAt = ts,
        )
        deliveryDao.saveAggregate(delivery = dto.toEntity(), items = emptyList(), photos = emptyList())
        mutationDao.upsert(
            mut("m9", id, baseVersion = 1, payload = deliveryUpsertPayload(id, 1)).copy(
                conflictPending = true,
                lastError = """http 403 · {"error":"foreign_site","message":"Запись другого объекта"}""",
            ),
        )

        processor.processAll()

        assertTrue("наследие 1.0.29 разобрано", mutationDao.findFor("delivery", id).isEmpty())
        assertTrue("на сервер такую мутацию не шлём", deliveriesApi.upsertCalls.isEmpty())
        assertNull(deliveryDao.findById(id))
    }

    @Test
    fun foreignSite403_shipment_purged() = runBlocking {
        val id = "s2"
        val dto = ShipmentDto(id = id, status = StatusDto("st", "shipment", "confirmed_mol", "confirmed_mol", sortOrder = 40), kind = "contractor", siteId = "site-foreign", version = 1, createdAt = ts, updatedAt = ts)
        shipmentDao.saveAggregate(shipment = dto.toEntity(), items = emptyList(), photos = emptyList())
        mutationDao.upsert(mut("ms2", id, entityType = "shipment", baseVersion = 1, payload = shipmentUpsertPayload(id, 1)))
        shipmentsApi.onUpsert = { throw foreignSite403() }

        processor.processAll()

        assertNull(shipmentDao.findById(id))
        assertTrue(mutationDao.findFor("shipment", id).isEmpty())
    }

    private suspend fun seedDelivery(id: String, blob: File, thumb: File) {
        val dto = DeliveryDto(
            id = id,
            status = StatusDto("st", "delivery", "confirmed_mol", "confirmed_mol", sortOrder = 40),
            siteId = "site-foreign",
            version = 1,
            createdAt = ts,
            updatedAt = ts,
        )
        deliveryDao.saveAggregate(delivery = dto.toEntity(), items = emptyList(), photos = emptyList())
        deliveryDao.upsertPhoto(
            RemoteDeliveryPhotoEntity(
                id = "p-$id",
                deliveryId = id,
                kind = "cargo",
                s3Key = null,
                thumbS3Key = null,
                contentHash = null,
                takenAt = ts,
                uploadedAt = null,
                idempotencyKey = "idem-$id",
                contentType = "image/jpeg",
                localBlobPath = blob.absolutePath,
                localThumbPath = thumb.absolutePath,
                uploadStatus = "PENDING_UPLOAD",
                lastUploadError = null,
            ),
        )
    }

    private fun foreignSite403(): HttpException {
        val body = """{"error":"foreign_site","message":"Запись другого объекта"}"""
        return HttpException(Response.error<Any>(403, body.toResponseBody("application/json".toMediaType())))
    }

    // --- fakes ---

    private class FakeDeliveriesApi : DeliveriesApi {
        var onUpsert: (DeliveryUpsertRequest) -> DeliveryDto = { throw NotImplementedError() }
        var onMarkDeletion: (String) -> DeliveryDto = { throw NotImplementedError() }
        var onGet: (String) -> DeliveryDto = { throw NotImplementedError() }
        val upsertCalls = mutableListOf<DeliveryUpsertRequest>()

        override suspend fun list(trash: Int?, limit: Int?, offset: Int?): DeliveryListResponse = throw NotImplementedError()
        override suspend fun get(id: String): DeliveryDto = onGet(id)
        override suspend fun upsert(body: DeliveryUpsertRequest): DeliveryDto { upsertCalls += body; return onUpsert(body) }
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun markDeletion(id: String, body: MarkDeletionRequest): DeliveryDto = onMarkDeletion(id)
        override suspend fun unmarkDeletion(id: String): DeliveryDto = throw NotImplementedError()
    }

    private class FakeShipmentsApi : ShipmentsApi {
        var onUpsert: (ShipmentUpsertRequest) -> ShipmentDto = { throw NotImplementedError() }
        val upsertCalls = mutableListOf<ShipmentUpsertRequest>()

        override suspend fun list(trash: Int?, limit: Int?, offset: Int?): ShipmentListResponse = throw NotImplementedError()
        override suspend fun get(id: String): ShipmentDto = throw NotImplementedError()
        override suspend fun upsert(body: ShipmentUpsertRequest): ShipmentDto { upsertCalls += body; return onUpsert(body) }
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun markDeletion(id: String, body: MarkDeletionRequest): ShipmentDto = throw NotImplementedError()
        override suspend fun unmarkDeletion(id: String): ShipmentDto = throw NotImplementedError()
    }
}
