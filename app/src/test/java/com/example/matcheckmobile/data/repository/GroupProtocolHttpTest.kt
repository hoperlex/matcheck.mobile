package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.auth.SessionTokens
import com.example.matcheckmobile.data.auth.TokenStorage
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import com.example.matcheckmobile.data.remote.api.DeliveriesApi
import com.example.matcheckmobile.data.remote.api.ShipmentsApi
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertItem
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.net.AuthHeaderInterceptor
import com.example.matcheckmobile.data.remote.net.ClientCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.Continuation

/**
 * Групповой протокол через НАСТОЯЩИЙ HTTP: тот же перехватчик заголовков,
 * Retrofit и Json, что на планшете, и настоящий [MutationProcessor]. Сервер —
 * MockWebServer с ответами контракта group-operations.ts.
 *
 * Проверяется то, что не видно юнит-тестам по частям: что заголовок
 * возможностей и поля машины реально уходят в запрос, и что typed 409, 426 и
 * 503 доходят до правильного исхода очереди.
 */
class GroupProtocolHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val mutations = mutableMapOf<String, MutationEntity>()
    private val unfrozen = mutableListOf<String>()
    private val quarantined = mutableListOf<QuarantinedOperationEntity>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        root = Files.createTempDirectory("gph").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun processor(): MutationProcessor {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor(StubTokens, "matcheck-android/test"))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val mutationDao = fake<MutationDao> { name, args ->
            when (name) {
                "listForeignSiteFailures", "listFrozen401" -> emptyList<MutationEntity>()
                "listFrozen426" -> mutations.values.filter { it.conflictPending && it.lastError?.startsWith("http 426") == true }
                "listPending" -> mutations.values.filter { !it.conflictPending }.sortedBy { it.createdAt }
                "listConflicts" -> mutations.values.filter { it.conflictPending }
                "unfreezeIfFrozen" -> {
                    val id = args[0] as String
                    val m = mutations[id]
                    if (m != null && m.conflictPending) {
                        mutations[id] = m.copy(conflictPending = false, attempts = 0, nextAttemptAt = null)
                        unfrozen += id
                        1
                    } else 0
                }
                "upsert" -> { val m = args[0] as MutationEntity; mutations[m.id] = m; Unit }
                "deleteById" -> { mutations.remove(args[0] as String); Unit }
                else -> error("MutationDao.$name не ожидался")
            }
        }
        val gq = GroupQuarantine(
            port = object : GroupQuarantinePort {
                override fun observeAll(): Flow<List<QuarantinedOperationEntity>> = flowOf(quarantined)
                override suspend fun deliveryAggregate(deliveryId: String): DeliveryAggregate? = null
                override suspend fun commit(row: QuarantinedOperationEntity) {
                    quarantined += row
                    // как RoomGroupQuarantinePort: очередь сущности снимается в той же транзакции
                    mutations.values.removeAll { it.entityId == row.entityId }
                }
                override suspend fun find(id: String) = quarantined.firstOrNull { it.id == id }
                override suspend fun markReissued(id: String, at: Long) = Unit
                override suspend fun delete(id: String) = Unit
                override suspend fun sourceStillUsed(path: String) = false
            },
            filesDir = root,
            saveDraft = {},
        )
        return MutationProcessor(
            mutationDao = mutationDao,
            deliveryDao = fake<RemoteDeliveryDao> { name, _ -> error("RemoteDeliveryDao.$name не ожидался") },
            shipmentDao = fake<RemoteShipmentDao> { name, _ -> error("RemoteShipmentDao.$name не ожидался") },
            deliveriesApi = retrofit.create(DeliveriesApi::class.java),
            shipmentsApi = retrofit.create(ShipmentsApi::class.java),
            remoteWriter = PendingAwareRemoteWriter(
                fake { n, _ -> error("$n") }, fake { n, _ -> error("$n") }, mutationDao, NoTx,
            ),
            quarantine = ForeignSiteQuarantine(fake { n, _ -> error("$n") }, fake { n, _ -> error("$n") }, mutationDao, NoTx),
            groupQuarantine = gq,
            telemetryDiscard = {},
        )
    }

    private fun queue(
        payload: DeliveryUpsertRequest,
        frozenWith: String? = null,
    ): MutationEntity {
        val m = MutationEntity(
            id = "m-${mutations.size + 1}",
            entityType = "delivery",
            operation = "upsert",
            entityId = payload.id!!,
            baseVersion = 0,
            payloadJson = GroupQuarantine.JSON.encodeToString(DeliveryUpsertRequest.serializer(), payload),
            attempts = if (frozenWith != null) 1 else 0,
            nextAttemptAt = null,
            lastError = frozenWith,
            conflictPending = frozenWith != null,
            createdAt = mutations.size.toLong(),
        )
        mutations[m.id] = m
        return m
    }

    private val fresh = DeliveryUpsertRequest(
        id = "d-1",
        statusCode = "filled",
        siteId = "s-1",
        sourceDocumentIds = listOf("u-1", "u-2"),
        capabilities = ClientCapabilities.ALL,
        groupId = "11111111-1111-1111-1111-111111111111",
        groupRevision = 4,
    )

    @Test
    fun `запрос несёт заголовок возможностей и машину — group_claimed уводит операцию в карантин`() = runBlocking {
        queue(fresh)
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"group_claimed","groupId":"11111111-1111-1111-1111-111111111111",""" +
                    """"groupRevision":4,"claimedByOperationId":"22222222-2222-2222-2222-222222222222",""" +
                    """"claimedAt":"2026-11-20T09:15:00.000Z"}""",
            ),
        )

        processor().processAll()

        val request = server.takeRequest()
        assertEquals("/api/v1/deliveries", request.path)
        assertEquals(ClientCapabilities.ALL, request.getHeader(ClientCapabilities.HEADER))
        assertEquals("mobile", request.getHeader("X-Client-Type"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("11111111-1111-1111-1111-111111111111", body["groupId"]!!.jsonPrimitive.content)
        assertEquals(4, body["groupRevision"]!!.jsonPrimitive.int)
        assertEquals("source_groups_v1,group_claim_v1", body["capabilities"]!!.jsonPrimitive.content)

        // Операция целиком в карантине, очередь снята — FIFO не заблокирован.
        val row = quarantined.single()
        assertEquals("group_claimed", row.reason)
        assertEquals("2026-11-20T09:15:00.000Z", row.claimedAt)
        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `503 busy_retry — мутация остаётся в очереди и повторится`() = runBlocking {
        val m = queue(fresh)
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "2")
                .setBody("""{"error":"busy_retry","message":"повторите"}"""),
        )

        processor().processAll()

        val after = mutations[m.id]!!
        assertEquals(false, after.conflictPending)
        assertEquals(1, after.attempts)
        assertNotNull(after.nextAttemptAt)
        assertTrue(after.lastError!!.startsWith("http 503"))
        assertTrue(quarantined.isEmpty())
    }

    @Test
    fun `свой 426 — мутация заморожена с целыми данными, следующий цикл её не крутит`() = runBlocking {
        val m = queue(fresh)
        server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error":"client_outdated","message":"x"}"""))

        processor().processAll()
        val after = mutations[m.id]!!
        assertEquals(true, after.conflictPending)
        assertEquals(OWN_426_MARKER, after.lastError)
        assertEquals(m.payloadJson, after.payloadJson)

        // Второй цикл: разморозки нет, запросов к серверу нет.
        processor().processAll()
        assertTrue(unfrozen.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `очередь старой сборки после обновления — разморожена, проверена сервером, ушла в карантин`() = runBlocking {
        // Мутация старой сборки: без машины и возможностей, заморожена 426.
        val legacy = fresh.copy(capabilities = null, groupId = null, groupRevision = null)
        val m = queue(legacy, frozenWith = """http 426 · {"error":"client_outdated","message":"Обновите"}""")
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":"group_changed","groupId":"11111111-1111-1111-1111-111111111111","groupRevision":5}"""),
        )

        processor().processAll()

        assertEquals(listOf(m.id), unfrozen)
        val request = server.takeRequest()
        // Новая сборка шлёт заголовок и для чужого payload — сервер проверяет по новым правилам.
        assertEquals(ClientCapabilities.ALL, request.getHeader(ClientCapabilities.HEADER))
        assertNull(json.parseToJsonElement(request.body.readUtf8()).jsonObject["groupId"])
        assertEquals("group_changed", quarantined.single().reason)
        assertTrue(mutations.isEmpty())
    }

    /**
     * Тело «Завершить 1 Этап» сборки 1.0.41 так, как его собирает
     * DeliveryRepository.upsert из формы машины из двух УПД: позиции с
     * происхождением и финансами, госномер, комментарий, машина и версия состава.
     */
    private val stage1 = DeliveryUpsertRequest(
        id = "0b0b0b0b-0000-4000-8000-000000000001",
        statusCode = "filled",
        siteId = "5e5e5e5e-0000-4000-8000-000000000001",
        supplierId = "50505050-0000-4000-8000-000000000001",
        vehiclePlate = "А111АА77",
        arrivedAt = "2026-11-24T08:15:30.123456Z",
        comment = "1 Этап: \"без замечаний\"",
        sourceDocumentIds = listOf(
            "d0c00000-0000-4000-8000-000000000001",
            "d0c00000-0000-4000-8000-000000000002",
        ),
        items = listOf(
            DeliveryUpsertItem(
                id = "17e70000-0000-4000-8000-000000000001",
                sourceDocumentId = "d0c00000-0000-4000-8000-000000000001",
                sourceDocumentItemId = "5d170000-0000-4000-8000-000000000001",
                nameRaw = "Арматура А500С d12",
                qtyActual = "10.5",
                unit = "т",
                lineNo = 1,
                price = "65000.00",
                vatRate = "20",
                vatSum = "136500.00",
            ),
            DeliveryUpsertItem(
                id = "17e70000-0000-4000-8000-000000000002",
                sourceDocumentId = "d0c00000-0000-4000-8000-000000000002",
                sourceDocumentItemId = "5d170000-0000-4000-8000-000000000002",
                nameRaw = "Цемент М500",
                qtyActual = "40",
                unit = "шт",
                lineNo = 2,
                price = "450",
                vatRate = "20",
                vatSum = "3600",
            ),
        ),
        baseVersion = 0,
        capabilities = ClientCapabilities.ALL,
        groupId = "9a9a9a9a-0000-4000-8000-000000000001",
        groupRevision = 1,
    )

    /**
     * Формат запроса планшета — договор с сервером. Сверяется то, что реально
     * уходит по сети: мутация лежит в очереди в формате DeliveryRepository
     * (encodeDefaults = true), MutationProcessor её разбирает, Retrofit
     * сериализует Json-ом как в NetworkFactory (null и значения по умолчанию не
     * пишутся — поэтому нет inTransit, isAssets и unit "шт").
     *
     * ТА ЖЕ строка — эталон серверного теста matcheck
     * `apps/api/test/integration/mobile-delivery-body.int.test.ts`: он шлёт её
     * в POST /api/v1/deliveries. Поменялся формат — падает этот тест, и эталон
     * обновляется в ОБОИХ местах, иначе серверный тест проверяет не то, что
     * шлёт планшет.
     */
    @Test
    fun `тело 1 Этапа по сети совпадает с эталоном серверного теста`() = runBlocking {
        val m = MutationEntity(
            id = "m-stage1",
            entityType = "delivery",
            operation = "upsert",
            entityId = stage1.id!!,
            baseVersion = 0,
            payloadJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
                .encodeToString(DeliveryUpsertRequest.serializer(), stage1),
            attempts = 0,
            nextAttemptAt = null,
            lastError = null,
            conflictPending = false,
            createdAt = 0,
        )
        mutations[m.id] = m
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"busy_retry"}"""))

        processor().processAll()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/deliveries", request.path)
        assertEquals("mobile", request.getHeader("X-Client-Type"))
        assertEquals(ClientCapabilities.ALL, request.getHeader(ClientCapabilities.HEADER))
        assertEquals(
            json.parseToJsonElement(STAGE1_BODY_1_0_41),
            json.parseToJsonElement(request.body.readUtf8()),
        )
    }

    private object StubTokens : SessionTokens {
        override val accessToken: String = "access"
        override fun isAccessTokenValid(marginMillis: Long) = true
        override fun sessionKey(): TokenStorage.SessionKey = error("не нужен")
        override fun saveRefreshedTokensIfSessionMatches(
            expected: TokenStorage.SessionKey,
            accessToken: String,
            accessExpiresInSec: Long,
            refreshToken: String?,
            refreshExpiresInSec: Long?,
        ) = false
        override fun clearIfSessionMatches(expected: TokenStorage.SessionKey) = false
    }

    private object NoTx : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    /**
     * DAO-заглушка через Proxy: реализовывать интерфейс Room целиком ради
     * трёх методов незачем. Continuation (последний аргумент suspend-функции)
     * отбрасывается; непосредственный результат — это завершение без
     * приостановки, ровно как у Room при синхронном ответе.
     */
    private inline fun <reified T : Any> fake(noinline handler: (String, List<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fake ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handler(method.name, args.orEmpty().filterNot { it is Continuation<*> })
            }
        } as T

    companion object {
        /** Эталон тела 1 Этапа 1.0.41 — см. тест «тело 1 Этапа по сети…». */
        const val STAGE1_BODY_1_0_41 = """{
  "id": "0b0b0b0b-0000-4000-8000-000000000001",
  "statusCode": "filled",
  "siteId": "5e5e5e5e-0000-4000-8000-000000000001",
  "supplierId": "50505050-0000-4000-8000-000000000001",
  "vehiclePlate": "А111АА77",
  "arrivedAt": "2026-11-24T08:15:30.123456Z",
  "comment": "1 Этап: \"без замечаний\"",
  "sourceDocumentIds": ["d0c00000-0000-4000-8000-000000000001", "d0c00000-0000-4000-8000-000000000002"],
  "items": [
    {
      "id": "17e70000-0000-4000-8000-000000000001",
      "sourceDocumentId": "d0c00000-0000-4000-8000-000000000001",
      "sourceDocumentItemId": "5d170000-0000-4000-8000-000000000001",
      "nameRaw": "Арматура А500С d12",
      "qtyActual": "10.5",
      "unit": "т",
      "lineNo": 1,
      "price": "65000.00",
      "vatRate": "20",
      "vatSum": "136500.00"
    },
    {
      "id": "17e70000-0000-4000-8000-000000000002",
      "sourceDocumentId": "d0c00000-0000-4000-8000-000000000002",
      "sourceDocumentItemId": "5d170000-0000-4000-8000-000000000002",
      "nameRaw": "Цемент М500",
      "qtyActual": "40",
      "lineNo": 2,
      "price": "450",
      "vatRate": "20",
      "vatSum": "3600"
    }
  ],
  "baseVersion": 0,
  "capabilities": "source_groups_v1,group_claim_v1",
  "groupId": "9a9a9a9a-0000-4000-8000-000000000001",
  "groupRevision": 1
}"""
    }
}
