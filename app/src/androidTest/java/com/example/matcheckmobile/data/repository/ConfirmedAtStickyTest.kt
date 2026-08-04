package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import com.example.matcheckmobile.data.remote.api.dto.ShipmentUpsertRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Время фактического действия инспектора не сдвигается и не теряется.
 *
 * Ради чего тест: 04.08 на ЖК АЛИЯ очередь мутаций простояла 5 ч 15 мин и
 * доехала разом — сервер проставил четырём приёмкам одно время 05:23. Теперь
 * время рождается на планшете, и здесь проверяется, что оно переживает повтор
 * после ошибки фото, перезапуск процесса и приход серверного снимка без него.
 *
 * Настоящая Room (in-memory), а не fake: проверяется в том числе откат
 * транзакции, который подделка выполнить не может.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmedAtStickyTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveries: DeliveryRepository
    private lateinit var shipments: ShipmentRepository
    private lateinit var writer: PendingAwareRemoteWriter
    private val json = Json { ignoreUnknownKeys = true }

    private val siteId = UUID.randomUUID().toString()
    private val firstPress = "2026-08-03T20:53:00Z"
    private val muchLater = "2026-08-04T02:23:39Z"

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries().build()
        val tx = RoomTransactionRunner(db)
        deliveries = DeliveryRepository(
            deliveryDao = db.remoteDeliveryDao(),
            mutationDao = db.mutationDao(),
            localMetaDao = db.deliveryLocalMetaDao(),
            tx = tx,
        )
        shipments = ShipmentRepository(
            shipmentDao = db.remoteShipmentDao(),
            mutationDao = db.mutationDao(),
            localMetaDao = db.shipmentLocalMetaDao(),
            tx = tx,
        )
        writer = PendingAwareRemoteWriter(
            deliveryDao = db.remoteDeliveryDao(),
            shipmentDao = db.remoteShipmentDao(),
            mutationDao = db.mutationDao(),
            tx = tx,
        )
    }

    @After
    fun tearDown() = db.close()

    // ── helpers ──────────────────────────────────────────────────────────

    /** Ручной внос: одно время в оба поля, стабильный id черновика. */
    private fun manualEntryInput(draftId: String, at: String) = DeliveryRepository.UpsertInput(
        id = draftId,
        statusCode = "confirmed_mol",
        siteId = siteId,
        arrivedAt = at,
        confirmedByMolAt = at,
    )

    private fun manualDispatchInput(draftId: String, at: String) = ShipmentRepository.UpsertInput(
        id = draftId,
        statusCode = "confirmed_mol",
        kind = "contractor",
        siteId = siteId,
        shippedAt = at,
        confirmedByMolAt = at,
    )

    /** 2 Этап приёмки: заезд из 1 Этапа, подтверждение — момент нажатия. */
    private fun stage2Input(id: String, arrivedAt: String, confirmedAt: String) =
        DeliveryRepository.UpsertInput(
            id = id,
            statusCode = "confirmed_mol",
            siteId = siteId,
            arrivedAt = arrivedAt,
            confirmedByMolAt = confirmedAt,
        )

    private suspend fun queuedDeliveryPayload(id: String): DeliveryUpsertRequest? =
        db.mutationDao().findFor("delivery", id)
            .firstOrNull { it.operation == "upsert" }
            ?.payloadJson
            ?.let { json.decodeFromString(DeliveryUpsertRequest.serializer(), it) }

    private suspend fun queuedShipmentPayload(id: String): ShipmentUpsertRequest? =
        db.mutationDao().findFor("shipment", id)
            .firstOrNull { it.operation == "upsert" }
            ?.payloadJson
            ?.let { json.decodeFromString(ShipmentUpsertRequest.serializer(), it) }

    // ── ручные операции: время нажатия, а не синхронизации ───────────────

    @Test
    fun manualEntryKeepsPressTimeOffline() = runBlocking {
        val draftId = UUID.randomUUID().toString()

        val id = deliveries.upsert(manualEntryInput(draftId, firstPress))

        assertEquals("id ручного вноса — стабильный id черновика", draftId, id)
        val row = db.remoteDeliveryDao().findById(id)!!
        assertEquals(firstPress, row.confirmedByMolAt)
        assertEquals("для ручного вноса заезд и подтверждение — один момент", firstPress, row.arrivedAt)
        // Время видно в архиве СРАЗУ, ещё до синхронизации — ради этого всё.
        assertEquals(firstPress, queuedDeliveryPayload(id)!!.confirmedByMolAt)
    }

    @Test
    fun manualDispatchKeepsPressTimeOffline() = runBlocking {
        val draftId = UUID.randomUUID().toString()

        val id = shipments.upsert(manualDispatchInput(draftId, firstPress))

        assertEquals(draftId, id)
        val row = db.remoteShipmentDao().findById(id)!!
        assertEquals(firstPress, row.confirmedByMolAt)
        assertEquals(firstPress, row.shippedAt)
        assertEquals(firstPress, queuedShipmentPayload(id)!!.confirmedByMolAt)
    }

    // ── повтор после ошибки фото ─────────────────────────────────────────

    @Test
    fun repeatAfterPhotoFailureKeepsFirstTimeManualEntry() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        deliveries.upsert(manualEntryInput(draftId, firstPress))

        // Инспектор нажал «Завершить» второй раз — подготовка фото упала.
        val id = deliveries.upsert(manualEntryInput(draftId, muchLater))

        val row = db.remoteDeliveryDao().findById(id)!!
        assertEquals("время подтверждения не сдвинулось", firstPress, row.confirmedByMolAt)
        assertEquals("заезд тоже не сдвинулся", firstPress, row.arrivedAt)
        assertEquals(firstPress, queuedDeliveryPayload(id)!!.confirmedByMolAt)
    }

    @Test
    fun repeatAfterPhotoFailureKeepsFirstTimeManualDispatch() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        shipments.upsert(manualDispatchInput(draftId, firstPress))

        val id = shipments.upsert(manualDispatchInput(draftId, muchLater))

        val row = db.remoteShipmentDao().findById(id)!!
        assertEquals(firstPress, row.confirmedByMolAt)
        assertEquals(firstPress, row.shippedAt)
    }

    @Test
    fun repeatAfterPhotoFailureKeepsFirstTimeStage2() = runBlocking {
        val id = UUID.randomUUID().toString()
        val arrived = "2026-08-03T20:05:00Z"
        deliveries.upsert(stage2Input(id, arrived, firstPress))

        deliveries.upsert(stage2Input(id, arrived, muchLater))

        val row = db.remoteDeliveryDao().findById(id)!!
        assertEquals(firstPress, row.confirmedByMolAt)
        assertEquals(arrived, row.arrivedAt)
    }

    /** Дубли: повтор обязан обновлять ту же запись, а не заводить вторую. */
    @Test
    fun repeatDoesNotCreateSecondManualRecord() = runBlocking {
        val draftDelivery = UUID.randomUUID().toString()
        val draftShipment = UUID.randomUUID().toString()

        repeat(3) { deliveries.upsert(manualEntryInput(draftDelivery, firstPress)) }
        repeat(3) { shipments.upsert(manualDispatchInput(draftShipment, firstPress)) }

        assertEquals("три нажатия — одна приёмка", 1, db.remoteDeliveryDao().listReconcileVersions().size)
        assertEquals("три нажатия — одна отгрузка", 1, db.remoteShipmentDao().listReconcileVersions().size)
        assertNotNull(db.remoteDeliveryDao().findById(draftDelivery))
        assertNotNull(db.remoteShipmentDao().findById(draftShipment))
        // И ровно одна upsert-мутация на сущность — старая заменяется свежей.
        assertEquals(1, db.mutationDao().findFor("delivery", draftDelivery).size)
        assertEquals(1, db.mutationDao().findFor("shipment", draftShipment).size)
    }

    /**
     * «Перезапуск приложения»: пересобираем репозиторий поверх той же базы —
     * время должно жить и в записи, и в payload очереди.
     */
    @Test
    fun timestampSurvivesProcessRestart() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        deliveries.upsert(manualEntryInput(draftId, firstPress))

        val afterRestart = DeliveryRepository(
            deliveryDao = db.remoteDeliveryDao(),
            mutationDao = db.mutationDao(),
            localMetaDao = db.deliveryLocalMetaDao(),
            tx = RoomTransactionRunner(db),
        )
        afterRestart.upsert(manualEntryInput(draftId, muchLater))

        assertEquals(firstPress, db.remoteDeliveryDao().findById(draftId)!!.confirmedByMolAt)
        assertEquals(firstPress, queuedDeliveryPayload(draftId)!!.confirmedByMolAt)
    }

    // ── серверный снимок не стирает локальное время ──────────────────────

    @Test
    fun serverNullDoesNotEraseLocalTimeWhileMutationQueued() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        deliveries.upsert(manualEntryInput(draftId, firstPress))
        val local = db.remoteDeliveryDao().findById(draftId)!!

        // Push получил временную ошибку, мутация осталась в очереди, а pull
        // принёс запись в том виде, в каком она ещё лежит на сервере.
        writer.saveDelivery(
            delivery = local.copy(confirmedByMolAt = null, statusCode = "filled"),
            items = emptyList(),
            photos = emptyList(),
        )

        assertEquals(
            "пока мутация в очереди, локальное время сохраняется",
            firstPress,
            db.remoteDeliveryDao().findById(draftId)!!.confirmedByMolAt,
        )
    }

    @Test
    fun serverValueWinsOnceMutationLeftQueue() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        deliveries.upsert(manualEntryInput(draftId, firstPress))
        val local = db.remoteDeliveryDao().findById(draftId)!!

        // Мутация ушла (сервер ответил) — дальше он авторитетен целиком.
        db.mutationDao().deleteFor("delivery", draftId)
        writer.saveDelivery(
            delivery = local.copy(confirmedByMolAt = null, statusCode = "filled"),
            items = emptyList(),
            photos = emptyList(),
        )

        assertNull(
            "без мутации в очереди устаревшее локальное время не консервируем",
            db.remoteDeliveryDao().findById(draftId)!!.confirmedByMolAt,
        )
    }

    @Test
    fun serverTimeAlwaysWinsWhenNotNull() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        deliveries.upsert(manualEntryInput(draftId, firstPress))
        val local = db.remoteDeliveryDao().findById(draftId)!!

        writer.saveDelivery(
            delivery = local.copy(confirmedByMolAt = muchLater),
            items = emptyList(),
            photos = emptyList(),
        )

        assertEquals(muchLater, db.remoteDeliveryDao().findById(draftId)!!.confirmedByMolAt)
    }

    @Test
    fun shipmentServerNullDoesNotEraseLocalTimeWhileMutationQueued() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        shipments.upsert(manualDispatchInput(draftId, firstPress))
        val local = db.remoteShipmentDao().findById(draftId)!!

        writer.saveShipment(
            shipment = local.copy(confirmedByMolAt = null, statusCode = "shipped"),
            items = emptyList(),
            photos = emptyList(),
        )

        assertEquals(firstPress, db.remoteShipmentDao().findById(draftId)!!.confirmedByMolAt)
    }

    // ── атомарность: агрегат и мутация либо оба, либо ни одного ──────────

    /**
     * Настоящий откат Room: падаем внутри транзакции ПОСЛЕ записи агрегата, но
     * до мутации. Fake-runner, просто выполняющий блок, такое не поймает —
     * поэтому тест инструментальный.
     */
    @Test
    fun aggregateAndMutationRollBackTogether() = runBlocking {
        val id = UUID.randomUUID().toString()
        val tx = RoomTransactionRunner(db)

        val failed = runCatching {
            tx.run {
                db.remoteDeliveryDao().saveAggregate(
                    delivery = db.remoteDeliveryDao().findById(id) ?: newRow(id),
                    items = emptyList(),
                    photos = emptyList(),
                )
                error("сбой между записью агрегата и постановкой мутации")
            }
        }.isFailure

        assert(failed)
        assertNull("агрегат откатился вместе с транзакцией", db.remoteDeliveryDao().findById(id))
        assertEquals(0, db.mutationDao().findFor("delivery", id).size)
    }

    private fun newRow(id: String) = com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity(
        id = id,
        statusCode = "confirmed_mol",
        statusLabel = "confirmed_mol",
        statusColor = null,
        siteId = siteId,
        supplierId = null,
        contractorId = null,
        recipientMolId = null,
        vehiclePlate = null,
        driverName = null,
        arrivedAt = firstPress,
        inspectorId = null,
        comment = null,
        inTransit = false,
        isAssets = false,
        confirmedByMolUserId = null,
        confirmedByMolUserEmail = null,
        confirmedByMolAt = firstPress,
        pendingDeletionAt = null,
        pendingDeletionByUserId = null,
        pendingDeletionByUserEmail = null,
        pendingDeletionReason = null,
        version = 0,
        sourceDocumentIdsJson = "[]",
        createdAt = firstPress,
        updatedAt = firstPress,
        conflictPending = false,
        serverSnapshotJson = null,
        lastSyncError = null,
    )
}
