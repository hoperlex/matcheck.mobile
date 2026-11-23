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
}
