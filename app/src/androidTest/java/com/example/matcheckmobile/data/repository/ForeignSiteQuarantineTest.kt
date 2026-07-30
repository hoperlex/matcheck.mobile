package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
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
import java.io.File

/**
 * Карантин записей чужого объекта (403 foreign_site).
 *
 * Ключевое требование релиза — «без потерь данных»: пока у приёмки есть
 * неотправленный снимок с живым файлом, ни снимок, ни родитель не удаляются
 * автоматически. Удалить может только человек из «Очереди синхронизации».
 */
@RunWith(AndroidJUnit4::class)
class ForeignSiteQuarantineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveryDao: RemoteDeliveryDao
    private lateinit var shipmentDao: RemoteShipmentDao
    private lateinit var mutationDao: MutationDao
    private lateinit var quarantine: ForeignSiteQuarantine

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java).allowMainThreadQueries().build()
        deliveryDao = db.remoteDeliveryDao()
        shipmentDao = db.remoteShipmentDao()
        mutationDao = db.mutationDao()
        quarantine = ForeignSiteQuarantine(deliveryDao, shipmentDao, mutationDao, RoomTransactionRunner(db))
    }

    @After
    fun teardown() = db.close()

    // --- фикстуры ---

    private fun delivery(id: String) = RemoteDeliveryEntity(
        id = id, statusCode = "filled", statusLabel = "filled", statusColor = null,
        siteId = "site-foreign", supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = TS, inspectorId = null, comment = null,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = 1, sourceDocumentIdsJson = "[]",
        createdAt = TS, updatedAt = TS,
    )

    private fun photo(
        id: String,
        deliveryId: String,
        status: String = RemotePhotoStatus.PENDING_UPLOAD,
        blob: File? = null,
    ) = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = deliveryId, kind = "cargo", stage = "before",
        s3Key = null, thumbS3Key = null, contentHash = "hash-$id", takenAt = TS,
        uploadedAt = if (status == RemotePhotoStatus.UPLOADED) TS else null,
        idempotencyKey = "idem-$id", contentType = "image/jpeg",
        localBlobPath = blob?.absolutePath, localThumbPath = null,
        uploadStatus = status, lastUploadError = null,
    )

    private fun mutation(id: String, deliveryId: String) = MutationEntity(
        id = id, entityType = "delivery", operation = "upsert", entityId = deliveryId,
        baseVersion = 1, payloadJson = "{}", attempts = 1, nextAttemptAt = null,
        lastError = null, conflictPending = false, createdAt = 1L,
    )

    private fun blobFile(name: String): File = tmp.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    // --- тесты ---

    @Test
    fun quarantine_keepsUnsentPhotoAndParent_dropsQueue() = runBlocking {
        val id = "d-keep"
        val blob = blobFile("d-keep.jpg")
        deliveryDao.saveAggregate(delivery(id), emptyList(), listOf(photo("p1", id, blob = blob)))
        mutationDao.upsert(mutation("m1", id))

        val outcome = quarantine.quarantineDelivery(id)

        assertEquals(ForeignSiteQuarantine.Outcome.Quarantined(1), outcome)
        assertNotNull("родитель остался контейнером для снимка", deliveryDao.findById(id))
        val row = deliveryDao.findPhotoById("p1")!!
        assertEquals(RemotePhotoStatus.QUARANTINED_FOREIGN_SITE, row.uploadStatus)
        assertTrue("файл на диске сохранён", blob.exists())
        assertTrue("очередь записи снята", mutationDao.findFor("delivery", id).isEmpty())
        assertEquals(
            ForeignSiteQuarantine.QUARANTINE_REASON,
            deliveryDao.findById(id)!!.lastSyncError,
        )
    }

    @Test
    fun quarantinedPhotos_areNotPickedByUploadCycle() = runBlocking {
        val id = "d-cycle"
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(photo("p1", id, blob = blobFile("d-cycle.jpg"))),
        )

        quarantine.quarantineDelivery(id)

        val uploadable = deliveryDao.findPhotosByStatus(RemotePhotoStatus.UPLOADABLE)
        assertTrue("карантин не попадает в upload-цикл → нет вечного 403", uploadable.isEmpty())
    }

    @Test
    fun quarantine_deletesParent_whenNothingToSalvage() = runBlocking {
        val id = "d-drop"
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(photo("p1", id, status = RemotePhotoStatus.UPLOADED)),
        )
        mutationDao.upsert(mutation("m1", id))

        val outcome = quarantine.quarantineDelivery(id)

        assertEquals(ForeignSiteQuarantine.Outcome.Deleted, outcome)
        assertNull("спасать нечего — запись удалена", deliveryDao.findById(id))
        assertTrue(mutationDao.findFor("delivery", id).isEmpty())
    }

    @Test
    fun quarantine_isTerminalForPhotosWithLostBlob_whenOtherPhotoSurvives() = runBlocking {
        // Смешанный случай: один снимок спасаем, у второго blob потерян.
        // Второй тоже обязан стать терминальным, иначе он вечно ловит 403.
        val id = "d-mixed"
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(
                photo("p-live", id, blob = blobFile("d-mixed.jpg")),
                photo("p-lost", id, blob = null),
            ),
        )

        quarantine.quarantineDelivery(id)

        assertEquals(
            RemotePhotoStatus.QUARANTINED_FOREIGN_SITE,
            deliveryDao.findPhotoById("p-lost")!!.uploadStatus,
        )
        assertTrue(deliveryDao.findPhotosByStatus(RemotePhotoStatus.UPLOADABLE).isEmpty())
    }

    @Test
    fun protectedParents_listsQuarantineContainers() = runBlocking {
        val id = "d-protect"
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(photo("p1", id, blob = blobFile("d-protect.jpg"))),
        )
        quarantine.quarantineDelivery(id)

        val protectedParents = quarantine.protectedParents()

        assertEquals(listOf(id), protectedParents.deliveryIds)
        assertTrue(protectedParents.shipmentIds.isEmpty())
    }

    @Test
    fun deleteAll_removesFilesRowsAndEmptyParent() = runBlocking {
        val id = "d-delete"
        val blob = blobFile("d-delete.jpg")
        deliveryDao.saveAggregate(delivery(id), emptyList(), listOf(photo("p1", id, blob = blob)))
        quarantine.quarantineDelivery(id)

        val removed = quarantine.deleteAll()

        assertEquals(1, removed)
        assertFalse("файл удалён с диска", blob.exists())
        assertNull(deliveryDao.findPhotoById("p1"))
        assertNull("пустой контейнер тоже убран", deliveryDao.findById(id))
    }

    @Test
    fun deleteAll_keepsParentWithRemainingQueue() = runBlocking {
        val id = "d-busy"
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(photo("p1", id, blob = blobFile("d-busy.jpg"))),
        )
        quarantine.quarantineDelivery(id)
        // Мутация появилась уже после карантина (например, ручная правка).
        mutationDao.upsert(mutation("m-late", id))

        quarantine.deleteAll()

        assertNotNull("родитель с непустой очередью не удаляется", deliveryDao.findById(id))
    }

    @Test
    fun exportableFiles_returnsOnlyExistingBlobs() = runBlocking {
        val id = "d-export"
        val live = blobFile("d-export-live.jpg")
        val ghost = File(tmp.root, "never-written.jpg")
        deliveryDao.saveAggregate(
            delivery(id), emptyList(),
            listOf(photo("p-live", id, blob = live), photo("p-ghost", id, blob = ghost)),
        )
        quarantine.quarantineDelivery(id)

        val files = quarantine.exportableFiles()

        assertEquals(1, files.size)
        assertEquals(live.absolutePath, files.single().file.absolutePath)
    }

    private companion object {
        const val TS = "2026-07-29T00:00:00Z"
    }
}
