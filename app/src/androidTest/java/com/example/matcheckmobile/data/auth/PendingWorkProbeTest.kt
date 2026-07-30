package com.example.matcheckmobile.data.auth

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.entity.ManualDispatchDraftEntity
import com.example.matcheckmobile.data.local.entity.ManualEntryDraftEntity
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Барьер «не потерять неотправленное» при выходе и смене аккаунта.
 *
 * Главное, что проверяем: в подсчёт входит ВСЁ невосстановимое (включая
 * ручной внос, который легко забыть) и НЕ входит серверный snapshot — иначе
 * выход заблокировался бы навсегда, ведь приёмки на планшете есть всегда.
 */
@RunWith(AndroidJUnit4::class)
class PendingWorkProbeTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var probe: PendingWorkProbe

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries().build()
        probe = PendingWorkProbe(db)
    }

    @After
    fun teardown() = db.close()

    private fun delivery(id: String) = RemoteDeliveryEntity(
        id = id, statusCode = "confirmed_mol", statusLabel = "confirmed_mol", statusColor = null,
        siteId = "site-1", supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = TS, inspectorId = null, comment = null,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = 1, sourceDocumentIdsJson = "[]",
        createdAt = TS, updatedAt = TS,
    )

    private fun photo(id: String, deliveryId: String, status: String) = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = deliveryId, kind = "cargo", stage = "before",
        s3Key = null, thumbS3Key = null, contentHash = "h-$id", takenAt = TS,
        uploadedAt = if (status == RemotePhotoStatus.UPLOADED) TS else null,
        idempotencyKey = "i-$id", contentType = "image/jpeg",
        localBlobPath = "/tmp/$id.jpg", localThumbPath = null,
        uploadStatus = status, lastUploadError = null,
    )

    @Test
    fun serverSnapshotAlone_isNotBlocking() = runBlocking {
        // Приёмки и УПД восстановятся ре-синком. Если считать их, «Выйти»
        // перестанет работать в принципе — на планшете они есть всегда.
        db.remoteDeliveryDao().saveAggregate(
            delivery("d1"), emptyList(), listOf(photo("p1", "d1", RemotePhotoStatus.UPLOADED)),
        )

        val work = probe.probe()

        assertTrue("серверный snapshot не должен блокировать выход", work.isEmpty)
        assertFalse(work.probeFailed)
    }

    @Test
    fun manualEntryDraft_isCounted() = runBlocking {
        // Ровно этот случай пропускала первая версия барьера: ручной внос —
        // полноценная работа инспектора, а в подсчёт не входил.
        db.manualEntryDraftDao().upsert(
            ManualEntryDraftEntity(
                localDraftId = "m1", siteId = "site-1",
                documentPhotoPathsJson = "[]", cargoPhotoPathsJson = "[]",
                manualUpdText = "", materialsJson = "[]", commentText = "",
                createdAt = 1L, updatedAt = 1L,
            ),
        )

        val work = probe.probe()

        assertEquals(1, work.drafts)
        assertFalse(work.isEmpty)
    }

    @Test
    fun manualDispatchDraft_isCounted() = runBlocking {
        db.manualDispatchDraftDao().upsert(
            ManualDispatchDraftEntity(
                localDraftId = "md1", siteId = "site-1",
                documentPhotoPathsJson = "[]", cargoPhotoPathsJson = "[]",
                manualUpdText = "", materialsJson = "[]", commentText = "",
                shipmentPurpose = null, createdAt = 1L, updatedAt = 1L,
            ),
        )

        assertEquals(1, probe.probe().drafts)
    }

    @Test
    fun unsentPhotoAndQuarantine_countedSeparately() = runBlocking {
        db.remoteDeliveryDao().saveAggregate(delivery("d2"), emptyList(), emptyList())
        db.remoteDeliveryDao().upsertPhoto(photo("p-pending", "d2", RemotePhotoStatus.PENDING_UPLOAD))
        db.remoteDeliveryDao().upsertPhoto(
            photo("p-quarantine", "d2", RemotePhotoStatus.QUARANTINED_FOREIGN_SITE),
        )

        val work = probe.probe()

        assertEquals("карантин не должен попадать в обычные фото", 1, work.photos)
        assertEquals(1, work.quarantinedPhotos)
        // Карантин синхронизацией не лечится, но незалитое фото — да.
        assertTrue(work.syncCanHelp)
    }

    @Test
    fun quarantineOnly_syncCannotHelp() = runBlocking {
        db.remoteDeliveryDao().saveAggregate(delivery("d3"), emptyList(), emptyList())
        db.remoteDeliveryDao().upsertPhoto(
            photo("pq", "d3", RemotePhotoStatus.QUARANTINED_FOREIGN_SITE),
        )

        val work = probe.probe()

        assertFalse("предлагать «отправить» бессмысленно — сервер ответит 403", work.syncCanHelp)
        assertFalse(work.isEmpty)
    }

    @Test
    fun mutationQueue_isCounted() = runBlocking {
        db.mutationDao().upsert(
            MutationEntity(
                id = "m", entityType = "delivery", operation = "upsert", entityId = "d9",
                baseVersion = 1, payloadJson = "{}", attempts = 0, nextAttemptAt = null,
                lastError = null, conflictPending = false, createdAt = 1L,
            ),
        )

        assertEquals(1, probe.probe().mutations)
    }

    @Test
    fun probeFailure_isFailClosed() = runBlocking {
        // Закрытая база => любой запрос бросает. Барьер обязан трактовать это
        // как «данные могут быть», а не как «чисто»: молча стереть работу
        // инспектора из-за упавшего запроса недопустимо.
        db.close()

        val work = probe.probe()

        assertTrue(work.probeFailed)
        assertFalse("fail-closed: пустым такой результат считать нельзя", work.isEmpty)
        assertTrue(work.describe().contains("проверить очередь не удалось"))
    }

    private companion object {
        const val TS = "2026-07-30T00:00:00Z"
    }
}
