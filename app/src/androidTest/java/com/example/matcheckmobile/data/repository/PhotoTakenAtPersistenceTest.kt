package com.example.matcheckmobile.data.repository

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.remote.api.PhotosApi
import com.example.matcheckmobile.data.remote.api.dto.PhotoConfirmResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignRequest
import com.example.matcheckmobile.data.remote.api.dto.PhotoPresignResponse
import com.example.matcheckmobile.data.remote.api.dto.PhotoUrlResponse
import com.example.matcheckmobile.domain.model.PhotoIntent
import com.example.matcheckmobile.media.RemotePhotoStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Время съёмки фото живёт в Room и не пересчитывается при отправке.
 *
 * Ради чего: при долгом офлайне между съёмкой и presign проходят часы. Если бы
 * загрузчик перечитывал файл (или брал `Instant.now()`), на портале снова
 * оказалось бы время синхронизации — ровно тот симптом, что видели 04.08 на
 * ЖК ВАРШАВСКАЯ LIFE.
 *
 * Настоящая Room и настоящая подготовка кадра; сеть подменена фейком, который
 * запоминает presign-запрос.
 */
@RunWith(AndroidJUnit4::class)
class PhotoTakenAtPersistenceTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var prepare: PhotoPrepareProcessor
    private lateinit var uploader: PhotoUploadProcessor
    private val api = RecordingPhotosApi()

    private val siteId = UUID.randomUUID().toString()
    private val shotAt = "2026-08-04T09:27:00Z"
    private val deliveryId = UUID.randomUUID().toString()
    private val shipmentId = UUID.randomUUID().toString()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries().build()
        prepare = PhotoPrepareProcessor(
            deliveryDao = db.remoteDeliveryDao(),
            shipmentDao = db.remoteShipmentDao(),
            photoStorage = RemotePhotoStorage(ctx),
            toUri = { file -> Uri.fromFile(file) },
            freeSpaceBytes = { Long.MAX_VALUE },
        )
        uploader = PhotoUploadProcessor(
            deliveryDao = db.remoteDeliveryDao(),
            shipmentDao = db.remoteShipmentDao(),
            mutationDao = db.mutationDao(),
            photosApi = api,
            quarantine = ForeignSiteQuarantine(
                db.remoteDeliveryDao(), db.remoteShipmentDao(), db.mutationDao(),
                RoomTransactionRunner(db),
            ),
        )
        // Родительские записи: загрузчик берёт только те фото, чья запись уже
        // на сервере (version > 0) и без pending-мутации.
        db.remoteDeliveryDao().upsert(delivery(deliveryId))
        db.remoteShipmentDao().upsert(shipment(shipmentId))
    }

    @After
    fun tearDown() {
        db.close()
        File(ctx.filesDir, "remote_photos").listFiles()?.forEach { it.delete() }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Копия ассета в cacheDir — «снимок камеры», который отдаёт форма. */
    private fun sourceFile(): File {
        val dst = File.createTempFile("shot_", ".jpg", ctx.cacheDir)
        assets.open("1600x1200.jpg").use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        return dst
    }

    /**
     * Ставит фото в очередь так же, как это делает финализация формы:
     * durable-строка PENDING_PREPARE в той же транзакции, что и приёмка.
     */
    private suspend fun enqueueDeliveryIntent(src: File): String {
        val intent = PhotoIntent(kind = "cargo", stage = "before", sourcePath = src.absolutePath, takenAt = shotAt)
        val entity = intent.toDeliveryPhotoEntity(deliveryId)
        db.remoteDeliveryDao().insertPhotoIntents(listOf(entity))
        return entity.id
    }

    private suspend fun enqueueShipmentIntent(src: File): String {
        val intent = PhotoIntent(kind = "cargo", stage = "before", sourcePath = src.absolutePath, takenAt = shotAt)
        val entity = intent.toShipmentPhotoEntity(shipmentId)
        db.remoteShipmentDao().insertPhotoIntents(listOf(entity))
        return entity.id
    }

    @Test
    fun capturedTimeIsStoredInRoom() = runBlocking {
        val src = sourceFile()
        try {
            val photoId = enqueueDeliveryIntent(src)

            val row = db.remoteDeliveryDao().findPhotoById(photoId)
            assertNotNull(row)
            assertEquals("время съёмки, а не подготовки", shotAt, row!!.takenAt)
        } finally {
            src.delete()
        }
    }

    /**
     * Главный тест: между съёмкой и отправкой планшет перезапустили, а исходный
     * файл камеры удалили. Подготовленный blob в remote_photos/ не трогаем —
     * без него загрузка физически не состоится, и проверялось бы не то.
     */
    @Test
    fun presignUsesStoredTimeAfterRestartAndSourceLoss() = runBlocking {
        val src = sourceFile()
        val photoId = enqueueDeliveryIntent(src)
        // Подготовка кадра: main+thumb собраны, исходник больше не нужен.
        prepare.processAll()
        // «Перезапуск»: исходник камеры удалён, загрузчик собран заново.
        src.delete()
        assertFalse(src.exists())

        uploader.processAll()

        val sent = api.lastPresign
        assertNotNull("presign не вызывался", sent)
        assertEquals(shotAt, sent!!.takenAt)
        assertEquals(photoId, api.lastPresignPhotoIdMatchedBy(sent))
    }

    @Test
    fun shipmentPhotoKeepsTimeToo() = runBlocking {
        val src = sourceFile()
        try {
            val photoId = enqueueShipmentIntent(src)

            assertEquals(shotAt, db.remoteShipmentDao().findPhotoById(photoId)!!.takenAt)
        } finally {
            src.delete()
        }
    }

    // ── фикстуры ─────────────────────────────────────────────────────────

    private fun delivery(id: String) = RemoteDeliveryEntity(
        id = id, statusCode = "filled", statusLabel = "filled", statusColor = null,
        siteId = siteId, supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = shotAt, inspectorId = null,
        comment = null, inTransit = false, isAssets = false,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null,
        pendingDeletionByUserEmail = null, pendingDeletionReason = null,
        version = 1, sourceDocumentIdsJson = "[]", createdAt = shotAt, updatedAt = shotAt,
        conflictPending = false, serverSnapshotJson = null, lastSyncError = null,
    )

    private fun shipment(id: String) = RemoteShipmentEntity(
        id = id, statusCode = "shipped", statusLabel = "shipped", statusColor = null,
        kind = "contractor", purpose = null, inTransit = false, isAssets = false,
        siteId = siteId, receiverCounterpartyId = null, receiverMolId = null, destSiteId = null,
        vehiclePlate = null, driverName = null, shippedAt = shotAt, inspectorId = null,
        comment = null,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null,
        pendingDeletionByUserEmail = null, pendingDeletionReason = null,
        version = 1, sourceDocumentIdsJson = "[]", createdAt = shotAt, updatedAt = shotAt,
        conflictPending = false, serverSnapshotJson = null, lastSyncError = null,
    )

    /** Запоминает presign-запрос; PUT в S3 не происходит — URL пустой. */
    private class RecordingPhotosApi : PhotosApi {
        var lastPresign: PhotoPresignRequest? = null
        private val photoIds = mutableMapOf<PhotoPresignRequest, String>()

        fun lastPresignPhotoIdMatchedBy(req: PhotoPresignRequest): String? = photoIds[req]

        override suspend fun presign(body: PhotoPresignRequest): PhotoPresignResponse {
            lastPresign = body
            val id = UUID.randomUUID().toString()
            photoIds[body] = id
            // alreadyExists=true: PUT в S3 не нужен, тест проверяет payload
            // presign, а не транспорт.
            return PhotoPresignResponse(
                photoId = id,
                s3Key = "k/$id.jpg",
                thumbS3Key = null,
                alreadyExists = true,
                uploadUrl = null,
                thumbUploadUrl = null,
            )
        }

        override suspend fun confirm(id: String) = PhotoConfirmResponse(uploadedAt = "2026-08-04T09:31:14Z")
        override suspend fun url(id: String, thumb: Boolean?) = PhotoUrlResponse(url = "", expiresIn = 600)
        override suspend fun delete(id: String) = Unit
    }
}

private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
