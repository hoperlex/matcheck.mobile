package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.settings.DeviceSettings
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Частичный сброс snapshot при смене объекта (Б3).
 *
 * Проверяем ровно то, что ломалось раньше: долг переживает перезапуск
 * процесса, курсор чистится до удаления, а записи с несохранённой работой
 * инспектора сброс пропускает.
 */
@RunWith(AndroidJUnit4::class)
class SiteChangeResetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MatcheckDatabase
    private lateinit var settings: DeviceSettings
    private lateinit var quarantine: ForeignSiteQuarantine

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java).allowMainThreadQueries().build()
        settings = DeviceSettings(ctx)
        // DataStore общий на процесс — начинаем с чистого состояния.
        settings.clearPendingSiteReset()
        settings.clearSyncCursor()
        quarantine = ForeignSiteQuarantine(
            db.remoteDeliveryDao(), db.remoteShipmentDao(), db.mutationDao(), RoomTransactionRunner(db),
        )
    }

    @After
    fun teardown() = runBlocking {
        settings.clearPendingSiteReset()
        settings.clearSyncCursor()
        db.close()
    }

    private fun newReset() = SiteChangeReset(
        deviceSettings = settings,
        deliveryDao = db.remoteDeliveryDao(),
        shipmentDao = db.remoteShipmentDao(),
        sourceDocumentDao = db.remoteSourceDocumentDao(),
        mutationDao = db.mutationDao(),
        quarantine = quarantine,
        tx = RoomTransactionRunner(db),
    )

    // --- фикстуры ---

    private fun delivery(id: String) = RemoteDeliveryEntity(
        id = id, statusCode = "filled", statusLabel = "filled", statusColor = null,
        siteId = "site-old", supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = TS, inspectorId = null, comment = null,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = 1, sourceDocumentIdsJson = "[]",
        createdAt = TS, updatedAt = TS,
    )

    private fun shipment(id: String) = RemoteShipmentEntity(
        id = id, statusCode = "shipped", statusLabel = "shipped", statusColor = null,
        kind = "contractor", purpose = null, siteId = "site-old", receiverCounterpartyId = null,
        receiverMolId = null, destSiteId = null, vehiclePlate = null, driverName = null,
        shippedAt = TS, inspectorId = null, comment = null, confirmedByMolUserId = null,
        confirmedByMolUserEmail = null, confirmedByMolAt = null, pendingDeletionAt = null,
        pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = 1, sourceDocumentIdsJson = "[]",
        createdAt = TS, updatedAt = TS,
    )

    private fun photo(id: String, deliveryId: String, status: String) = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = deliveryId, kind = "cargo", stage = "before",
        s3Key = null, thumbS3Key = null, contentHash = "hash-$id", takenAt = TS,
        uploadedAt = if (status == RemotePhotoStatus.UPLOADED) TS else null,
        idempotencyKey = "idem-$id", contentType = "image/jpeg",
        localBlobPath = tmp.newFile("$id.jpg").apply { writeBytes(byteArrayOf(7)) }.absolutePath,
        localThumbPath = null, uploadStatus = status, lastUploadError = null,
    )

    private suspend fun seedPlain(vararg ids: String) {
        ids.forEach { db.remoteDeliveryDao().saveAggregate(delivery(it), emptyList(), emptyList()) }
    }

    // --- тесты ---

    @Test
    fun noPendingDebt_isNoOp() = runBlocking {
        seedPlain("d1")

        assertFalse(newReset().resumeIfNeeded())
        assertNotNull("без долга ничего не удаляем", db.remoteDeliveryDao().findById("d1"))
    }

    @Test
    fun keepIds_empty_wipesEverything() = runBlocking {
        seedPlain("d1", "d2", "d3")
        db.remoteShipmentDao().saveAggregate(shipment("s1"), emptyList(), emptyList())
        settings.setSyncCursor("2026-07-29T00:00:00Z")

        newReset().apply { markPending("site-new") }.resumeIfNeeded()

        assertEquals(0, db.remoteDeliveryDao().listReconcileVersions().size)
        assertEquals(0, db.remoteShipmentDao().listReconcileVersions().size)
        assertNull("курсор снят — следующий pull пойдёт как initial", settings.readSyncCursor())
    }

    @Test
    fun keepIds_single_protectsRecordWithUnsentPhoto() = runBlocking {
        seedPlain("d-plain")
        db.remoteDeliveryDao().saveAggregate(
            delivery("d-photo"), emptyList(),
            listOf(photo("p1", "d-photo", RemotePhotoStatus.PENDING_UPLOAD)),
        )

        val reset = newReset()
        reset.markPending("site-new")
        reset.resumeIfNeeded()

        assertNull("обычная запись стёрта", db.remoteDeliveryDao().findById("d-plain"))
        assertNotNull("запись с незалитым фото сохранена", db.remoteDeliveryDao().findById("d-photo"))
        assertNotNull("само фото тоже (иначе каскад бы его снёс)", db.remoteDeliveryDao().findPhotoById("p1"))
    }

    @Test
    fun keepIds_many_protectQuarantineQueueAndPhotos() = runBlocking {
        seedPlain("d-plain")
        // 1) карантин чужого объекта
        db.remoteDeliveryDao().saveAggregate(
            delivery("d-quarantine"), emptyList(),
            listOf(photo("pq", "d-quarantine", RemotePhotoStatus.PENDING_UPLOAD)),
        )
        quarantine.quarantineDelivery("d-quarantine")
        // 2) незалитое фото
        db.remoteDeliveryDao().saveAggregate(
            delivery("d-photo"), emptyList(),
            listOf(photo("pp", "d-photo", RemotePhotoStatus.UPLOAD_ERROR)),
        )
        // 3) непустая очередь мутаций
        db.remoteDeliveryDao().saveAggregate(delivery("d-queued"), emptyList(), emptyList())
        db.mutationDao().upsert(
            MutationEntity(
                id = "m1", entityType = "delivery", operation = "upsert", entityId = "d-queued",
                baseVersion = 1, payloadJson = "{}", attempts = 0, nextAttemptAt = null,
                lastError = null, conflictPending = false, createdAt = 1L,
            ),
        )

        val reset = newReset()
        reset.markPending("site-new")
        reset.resumeIfNeeded()

        assertNull(db.remoteDeliveryDao().findById("d-plain"))
        assertNotNull(db.remoteDeliveryDao().findById("d-quarantine"))
        assertNotNull(db.remoteDeliveryDao().findById("d-photo"))
        assertNotNull(db.remoteDeliveryDao().findById("d-queued"))
    }

    @Test
    fun debtSurvivesProcessDeath_andIsIdempotent() = runBlocking {
        seedPlain("d1")
        // Процесс «умер» сразу после markPending: сброс не выполнялся.
        newReset().markPending("site-new")

        // Новый экземпляр = новый запуск приложения.
        val afterRestart = newReset()
        assertTrue("долг найден в DataStore", afterRestart.resumeIfNeeded())
        assertNull(db.remoteDeliveryDao().findById("d1"))

        // Повтор — уже no-op, второй раз базу не трогаем.
        seedPlain("d2")
        assertFalse(afterRestart.resumeIfNeeded())
        assertNotNull("идемпотентность: свежая запись уцелела", db.remoteDeliveryDao().findById("d2"))
    }

    @Test
    fun markPending_writesNewSiteImmediately() = runBlocking {
        newReset().markPending("site-new")

        assertEquals("site-new", settings.currentSiteIdFlow.first())
        assertEquals("site-new", settings.readPendingSiteReset())
    }

    private companion object {
        const val TS = "2026-07-29T00:00:00Z"
    }
}
