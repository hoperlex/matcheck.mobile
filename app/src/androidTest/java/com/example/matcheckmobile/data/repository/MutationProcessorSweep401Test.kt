package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
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

/**
 * Разбор наследия 401 в [MutationProcessor].
 *
 * Инцидент 28.08.2026: сервер ответил 401 (протухший access-токен) на отправку
 * подтверждения 2 Этапа, `classifyFailure` счёл это неисправимой ошибкой,
 * заморозил мутацию — и работа инспектора пропала молча. Здесь проверяется, что
 * после правки такие мутации возвращаются в очередь, что sweep не трогает
 * здоровые и что повторная доставка не плодит дублей.
 *
 * in-memory Room + fake retrofit-API. ЗАПУСК — на устройстве/эмуляторе.
 */
@RunWith(AndroidJUnit4::class)
class MutationProcessorSweep401Test {

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveryDao: RemoteDeliveryDao
    private lateinit var shipmentDao: RemoteShipmentDao
    private lateinit var mutationDao: MutationDao
    private lateinit var deliveriesApi: FakeDeliveriesApi
    private lateinit var shipmentsApi: FakeShipmentsApi
    private lateinit var processor: MutationProcessor

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val ts = "2026-08-28T00:00:00Z"

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

    /** Ровно то, что пишет в lastError ветка Drop: "http $code · $rawSnippet". */
    private val error401 = """http 401 · {"error":"unauthorized"}"""

    private fun payload(id: String, baseVersion: Int? = 1): String = json.encodeToString(
        DeliveryUpsertRequest.serializer(),
        DeliveryUpsertRequest(id = id, statusCode = "confirmed_mol", siteId = "site-1", baseVersion = baseVersion),
    )

    private fun mut(
        mid: String,
        entityId: String,
        entityType: String = "delivery",
        op: String = "upsert",
        baseVersion: Int? = 1,
        payloadJson: String? = null,
        frozen: Boolean = true,
        lastError: String? = null,
        attempts: Int = 1,
        nextAttemptAt: Long? = null,
        createdAt: Long = 1L,
    ) = MutationEntity(
        id = mid, entityType = entityType, operation = op, entityId = entityId,
        baseVersion = baseVersion, payloadJson = payloadJson, attempts = attempts,
        nextAttemptAt = nextAttemptAt, lastError = lastError ?: error401,
        conflictPending = frozen, createdAt = createdAt,
    )

    private fun dto(id: String, code: String = "filled", version: Int = 1) = DeliveryDto(
        id = id,
        status = StatusDto("st", "delivery", code, code, sortOrder = 40),
        siteId = "site-1",
        version = version,
        createdAt = ts,
        updatedAt = ts,
    )

    private suspend fun saveLocal(id: String) =
        deliveryDao.saveAggregate(delivery = dto(id).toEntity(), items = emptyList(), photos = emptyList())

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private fun http401() = http(401, """{"error":"unauthorized"}""")
    private fun http500() = http(500, """{"error":"internal"}""")

    // --- sweep: выборка ---

    @Test
    fun freshBackoff401_notTouchedBySweep() = runBlocking {
        // После правки свежий 401 уходит в Backoff: та же ошибка в lastError, но
        // мутация рабочая. Sweep обязан пройти мимо, иначе он сбросит attempts
        // и сломает экспоненциальный backoff, перестав быть одноразовым.
        val id = "d-fresh"
        saveLocal(id)
        val future = System.currentTimeMillis() + 600_000
        mutationDao.upsert(
            mut("m1", id, payloadJson = payload(id), frozen = false, attempts = 3, nextAttemptAt = future),
        )

        processor.processAll()

        val m = mutationDao.findFor("delivery", id).single()
        assertEquals("attempts здоровой мутации не сброшены", 3, m.attempts)
        assertEquals("backoff-пауза сохранена", future, m.nextAttemptAt)
        assertTrue("в backoff-паузе мутация не отправляется", deliveriesApi.upsertCalls.isEmpty())
    }

    @Test
    fun frozenConflict_notTouchedBySweep() = runBlocking {
        // Конфликты пишут lastError = "conflict(v=N)" и под фильтр не подпадают.
        val id = "d-conflict"
        saveLocal(id)
        mutationDao.upsert(mut("m1", id, payloadJson = payload(id), lastError = "conflict(v=2)"))

        processor.processAll()

        val m = mutationDao.findFor("delivery", id).single()
        assertTrue("конфликт остаётся замороженным", m.conflictPending)
        assertTrue(deliveriesApi.upsertCalls.isEmpty())
    }

    // --- sweep: политика по операциям ---

    @Test
    fun frozenUpsertWithParent_unfrozen_sameOperationReplayed() = runBlocking {
        val id = "d-upsert"
        saveLocal(id)
        val body = payload(id)
        mutationDao.upsert(mut("m1", id, payloadJson = body, createdAt = 777L))
        // 500 — чтобы мутация осталась в очереди и её поля можно было проверить.
        deliveriesApi.onUpsert = { throw http500() }

        processor.processAll()

        val m = mutationDao.findFor("delivery", id).single()
        assertFalse("мутация разморожена", m.conflictPending)
        assertEquals("id тот же", "m1", m.id)
        assertEquals("payload не пересобран", body, m.payloadJson)
        assertEquals("baseVersion сохранён", 1, m.baseVersion)
        assertEquals("createdAt сохранён — на нём держится FIFO", 777L, m.createdAt)
        assertEquals("операция повторена ровно та же", 1, deliveriesApi.upsertCalls.size)
        assertEquals(id, deliveriesApi.upsertCalls.single().id)
    }

    @Test
    fun frozenDeleteWithoutParent_unfrozen() = runBlocking {
        // Ключевой случай: hard-delete СНАЧАЛА сносит локальную запись и только
        // потом ставит мутацию, поэтому замороженный delete всегда «осиротевший».
        // Выбросить его значило бы навсегда оставить запись на сервере.
        val id = "d-deleted"
        assertNull("родителя нет — так и задумано", deliveryDao.findById(id))
        mutationDao.upsert(mut("m1", id, op = "delete", payloadJson = null))

        processor.processAll()

        assertTrue("мутация доставлена и снята с очереди", mutationDao.findFor("delivery", id).isEmpty())
        assertEquals("delete ушёл на сервер", listOf(id), deliveriesApi.deleteCalls)
    }

    @Test
    fun frozenUpsertWithoutParent_discarded() = runBlocking {
        // Родителя нет и это не delete → сервер прислал tombstone, локальную
        // строку снёс SyncRepository. Разморозка воскресила бы удалённую запись.
        val id = "d-tombstoned"
        mutationDao.upsert(mut("m1", id, payloadJson = payload(id)))

        processor.processAll()

        assertTrue("осиротевший upsert удалён", mutationDao.findFor("delivery", id).isEmpty())
        assertTrue("на сервер ничего не ушло", deliveriesApi.upsertCalls.isEmpty())
    }

    @Test
    fun frozenUnknownOperation_keptFrozen() = runBlocking {
        val id = "d-unknown"
        saveLocal(id)
        mutationDao.upsert(mut("m1", id, op = "rename", payloadJson = payload(id)))

        processor.processAll()

        val m = mutationDao.findFor("delivery", id).single()
        assertTrue("незнакомое не размораживаем и не удаляем", m.conflictPending)
    }

    @Test
    fun sweepIsIdempotent_secondRunIsNoop() = runBlocking {
        val id = "d-twice"
        saveLocal(id)
        mutationDao.upsert(mut("m1", id, payloadJson = payload(id)))
        deliveriesApi.onUpsert = { throw http500() }

        processor.processAll()
        val afterFirst = mutationDao.findFor("delivery", id).single()
        processor.processAll()
        val afterSecond = mutationDao.findFor("delivery", id).single()

        assertFalse(afterSecond.conflictPending)
        assertEquals("повторный проход не сбрасывает attempts", afterFirst.attempts, afterSecond.attempts)
        assertEquals(afterFirst.nextAttemptAt, afterSecond.nextAttemptAt)
    }

    // --- доставка после разморозки ---

    @Test
    fun retryAfter401_succeeds_withoutDuplicate() = runBlocking {
        val id = "d-retry"
        saveLocal(id)
        val body = payload(id)
        mutationDao.upsert(mut("m1", id, payloadJson = body, frozen = false, attempts = 0))
        deliveriesApi.onUpsert = { throw http401() }

        processor.processAll()

        val afterFail = mutationDao.findFor("delivery", id).single()
        assertFalse("401 больше не замораживает мутацию", afterFail.conflictPending)
        assertNotNull("ушла в backoff-паузу", afterFail.nextAttemptAt)

        // Снимаем паузу вместо ожидания реального таймера.
        mutationDao.upsert(afterFail.copy(nextAttemptAt = null))
        deliveriesApi.onUpsert = { dto(id, code = "confirmed_mol", version = 2) }

        processor.processAll()

        assertTrue("мутация доставлена и снята", mutationDao.findFor("delivery", id).isEmpty())
        assertEquals("ровно две попытки — дубля не создано", 2, deliveriesApi.upsertCalls.size)
        assertTrue("обе попытки везут ту же операцию", deliveriesApi.upsertCalls.all { it.id == id })
        assertEquals("confirmed_mol", deliveryDao.findById(id)!!.statusCode)
    }

    @Test
    fun fifoPreserved_unfrozenOlderGoesFirstAndBlocksNewer() = runBlocking {
        val id = "d-fifo"
        saveLocal(id)
        val older = payload(id, baseVersion = 1)
        val newer = payload(id, baseVersion = 2)
        mutationDao.upsert(mut("m-old", id, payloadJson = older, createdAt = 1L))
        mutationDao.upsert(
            mut("m-new", id, payloadJson = newer, baseVersion = 2, frozen = false, attempts = 0, createdAt = 2L),
        )
        deliveriesApi.onUpsert = { throw http500() }

        processor.processAll()

        assertEquals("отправлена только старшая мутация", 1, deliveriesApi.upsertCalls.size)
        assertEquals("порядок FIFO по createdAt", 1, deliveriesApi.upsertCalls.single().baseVersion)
        assertEquals("обе на месте", 2, mutationDao.findFor("delivery", id).size)
    }

    // --- fakes ---

    private class FakeDeliveriesApi : DeliveriesApi {
        var onUpsert: (DeliveryUpsertRequest) -> DeliveryDto = { throw NotImplementedError() }
        var onGet: (String) -> DeliveryDto = { throw NotImplementedError() }
        val upsertCalls = mutableListOf<DeliveryUpsertRequest>()
        val deleteCalls = mutableListOf<String>()

        override suspend fun list(trash: Int?, limit: Int?, offset: Int?): DeliveryListResponse = throw NotImplementedError()
        override suspend fun get(id: String): DeliveryDto = onGet(id)
        override suspend fun upsert(body: DeliveryUpsertRequest): DeliveryDto { upsertCalls += body; return onUpsert(body) }
        override suspend fun delete(id: String) { deleteCalls += id }
        override suspend fun markDeletion(id: String, body: MarkDeletionRequest): DeliveryDto = throw NotImplementedError()
        override suspend fun unmarkDeletion(id: String): DeliveryDto = throw NotImplementedError()
    }

    private class FakeShipmentsApi : ShipmentsApi {
        override suspend fun list(trash: Int?, limit: Int?, offset: Int?): ShipmentListResponse = throw NotImplementedError()
        override suspend fun get(id: String): ShipmentDto = throw NotImplementedError()
        override suspend fun upsert(body: ShipmentUpsertRequest): ShipmentDto = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun markDeletion(id: String, body: MarkDeletionRequest): ShipmentDto = throw NotImplementedError()
        override suspend fun unmarkDeletion(id: String): ShipmentDto = throw NotImplementedError()
    }
}
