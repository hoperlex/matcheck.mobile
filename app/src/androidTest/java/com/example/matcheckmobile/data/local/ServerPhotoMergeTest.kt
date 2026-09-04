package com.example.matcheckmobile.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Мердж серверных фото поверх локальных строк.
 *
 * Контекст: `RemoteMappers.toEntity` безусловно ставит localBlobPath и
 * localThumbPath в null, а backfill на 2 Этапе писал такой DTO обычным
 * upsert'ом. В результате открытие 2 Этапа само стирало указатели на файлы,
 * лежащие на диске, и показ фото уходил в S3 — с «фото недоступно» при любой
 * осечке сети. Здесь проверяется, что этого больше не происходит.
 */
@RunWith(AndroidJUnit4::class)
class ServerPhotoMergeTest {

    private lateinit var db: MatcheckDatabase
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val deliveryId = UUID.randomUUID().toString()
    private val TS = "2026-09-04T10:00:00Z"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.remoteDeliveryDao().upsert(delivery(version = 1))
    }

    @After
    fun tearDown() = db.close()

    private fun delivery(version: Int) = RemoteDeliveryEntity(
        id = deliveryId, statusCode = "filled", statusLabel = "filled", statusColor = null,
        siteId = "site-1", supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = TS, inspectorId = null, comment = null,
        confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
        pendingDeletionAt = null, pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = version, sourceDocumentIdsJson = "[]",
        createdAt = TS, updatedAt = TS,
    )

    private fun localPhoto(
        id: String,
        status: String,
        localBlobPath: String? = "/data/blob.jpg",
        localThumbPath: String? = "/data/blob.thumb.jpg",
        sourcePath: String? = "/data/source.jpg",
    ) = RemoteDeliveryPhotoEntity(
        id = id,
        deliveryId = deliveryId,
        kind = "cargo",
        stage = "before",
        s3Key = null,
        thumbS3Key = null,
        contentHash = "hash-local",
        takenAt = "2026-09-04T09:00:00Z",
        uploadedAt = null,
        idempotencyKey = id,
        contentType = "image/jpeg",
        localBlobPath = localBlobPath,
        localThumbPath = localThumbPath,
        uploadStatus = status,
        lastUploadError = null,
        sourcePath = sourcePath,
        preparingSince = null,
    )

    /** Серверный DTO ровно в том виде, в каком его строит RemoteMappers. */
    private fun serverPhoto(id: String, uploadedAt: String? = "2026-09-04T09:05:00Z") =
        RemoteDeliveryPhotoEntity(
            id = id,
            deliveryId = deliveryId,
            kind = "cargo",
            stage = "before",
            s3Key = "site/deliveries/$deliveryId/$id.jpg",
            thumbS3Key = null,
            contentHash = null,
            takenAt = "2026-09-04T09:00:00Z",
            uploadedAt = uploadedAt,
            idempotencyKey = "",
            contentType = "image/jpeg",
            localBlobPath = null,
            localThumbPath = null,
            uploadStatus = if (uploadedAt != null) RemotePhotoStatus.UPLOADED else RemotePhotoStatus.PENDING_UPLOAD,
            lastUploadError = null,
        )

    @Test
    fun mergeSavesLocalPathsAndFillsServerColumns() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()
        dao.upsertPhoto(localPhoto(id, RemotePhotoStatus.UPLOADED))

        dao.upsertServerPhoto(serverPhoto(id))

        val row = dao.findPhotoById(id)
        assertNotNull(row)
        // Главное: локальные указатели пережили мердж.
        assertEquals("/data/blob.jpg", row!!.localBlobPath)
        assertEquals("/data/blob.thumb.jpg", row.localThumbPath)
        assertEquals("/data/source.jpg", row.sourcePath)
        assertEquals(id, row.idempotencyKey)
        assertEquals("image/jpeg", row.contentType)
        // И серверные колонки при этом доехали.
        assertEquals("site/deliveries/$deliveryId/$id.jpg", row.s3Key)
        assertEquals("2026-09-04T09:05:00Z", row.uploadedAt)
        // contentHash считается локально — пустое серверное поле его не затирает.
        assertEquals("hash-local", row.contentHash)
    }

    @Test
    fun mergeDoesNotDowngradeLocalWorkInProgress() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()
        // Кадр ещё не подготовлен: blob'а нет, есть только исходник.
        dao.upsertPhoto(
            localPhoto(id, RemotePhotoStatus.PENDING_PREPARE, localBlobPath = null, localThumbPath = null),
        )

        dao.upsertServerPhoto(serverPhoto(id))

        val row = dao.findPhotoById(id)!!
        // Понижение до PENDING_UPLOAD отправило бы кадр в UPLOAD_ERROR
        // «blob missing» с последующим удалением строки.
        assertEquals(RemotePhotoStatus.PENDING_PREPARE, row.uploadStatus)
        assertEquals("/data/source.jpg", row.sourcePath)
    }

    @Test
    fun mergeHealsRowWithNothingLeftToSend() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()
        // Локально отправлять уже нечего, а сервер подтверждает загрузку.
        dao.upsertPhoto(
            localPhoto(id, RemotePhotoStatus.PENDING_UPLOAD, localBlobPath = null, sourcePath = null),
        )

        dao.upsertServerPhoto(serverPhoto(id))

        assertEquals(RemotePhotoStatus.UPLOADED, dao.findPhotoById(id)!!.uploadStatus)
    }

    @Test
    fun mergeSkipsServerRowThatWasNeverConfirmed() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()

        // presign без confirm: объекта в S3 нет, blob'а локально тоже. Заводить
        // такую строку — значит создать uploadable-запись без файла.
        dao.upsertServerPhoto(serverPhoto(id, uploadedAt = null))

        assertNull(dao.findPhotoById(id))
    }

    @Test
    fun mergeInsertsUnknownUploadedRowAsIs() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()

        dao.upsertServerPhoto(serverPhoto(id))

        val row = dao.findPhotoById(id)
        assertNotNull(row)
        assertEquals(RemotePhotoStatus.UPLOADED, row!!.uploadStatus)
    }

    @Test
    fun saveAggregateKeepsLocalPaths() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val id = UUID.randomUUID().toString()
        dao.upsertPhoto(localPhoto(id, RemotePhotoStatus.UPLOADED))

        dao.saveAggregate(
            delivery = delivery(version = 2),
            items = emptyList(),
            photos = listOf(serverPhoto(id)),
        )

        val row = dao.findPhotoById(id)!!
        assertEquals("/data/blob.thumb.jpg", row.localThumbPath)
        assertEquals("/data/source.jpg", row.sourcePath)
    }

    @Test
    fun photoIdSwapIsAtomicAndKeepsThumb() = runBlocking {
        val dao = db.remoteDeliveryDao()
        val clientId = UUID.randomUUID().toString()
        val serverId = UUID.randomUUID().toString()
        dao.upsertPhoto(localPhoto(clientId, RemotePhotoStatus.UPLOADING))

        val uploaded = localPhoto(clientId, RemotePhotoStatus.UPLOADED, localBlobPath = null)
            .copy(id = serverId, uploadedAt = "2026-09-04T09:05:00Z")
        dao.replaceUploadedPhotoId(clientId, uploaded)

        assertNull("старая строка обязана исчезнуть", dao.findPhotoById(clientId))
        val row = dao.findPhotoById(serverId)
        assertNotNull("новая строка обязана появиться", row)
        assertEquals("миниатюра переживает смену id", "/data/blob.thumb.jpg", row!!.localThumbPath)
        assertEquals(1, dao.findPhotosByDelivery(deliveryId).size)
    }
}
