package com.example.matcheckmobile.data.repository

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.domain.model.PhotoIntent
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import com.example.matcheckmobile.media.RemotePhotoStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Инварианты, ради которых вводился PENDING_PREPARE.
 *
 * Контекст: подготовка кадров шла ПОСЛЕ постановки мутации в очередь, и её
 * падение (нет места / OOM) оставляло приёмку без фото — молча и
 * безвозвратно. За 09.06–06.07.2026 так набралось 14 приёмок в финальном
 * статусе без единого подтверждённого фото. Проверяем, что теперь так не
 * бывает: кадр либо доезжает, либо остаётся в очереди и повторяется.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPreparePipelineTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveries: DeliveryRepository
    private lateinit var prepare: PhotoPrepareProcessor

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val siteId = UUID.randomUUID().toString()
    private val shotAt = "2026-08-12T02:33:10Z"

    /** Свободное место, которое видит процессор. Тест им управляет. */
    private var freeSpace = Long.MAX_VALUE

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries().build()
        deliveries = DeliveryRepository(
            deliveryDao = db.remoteDeliveryDao(),
            mutationDao = db.mutationDao(),
            localMetaDao = db.deliveryLocalMetaDao(),
            tx = RoomTransactionRunner(db),
        )
        prepare = PhotoPrepareProcessor(
            deliveryDao = db.remoteDeliveryDao(),
            shipmentDao = db.remoteShipmentDao(),
            photoStorage = RemotePhotoStorage(ctx),
            toUri = { file -> Uri.fromFile(file) },
            freeSpaceBytes = { freeSpace },
        )
    }

    @After
    fun tearDown() {
        db.close()
        File(ctx.filesDir, "remote_photos").listFiles()?.forEach { it.delete() }
        sources.forEach { it.delete() }
    }

    private val sources = mutableListOf<File>()

    private fun sourceFile(): File {
        val dst = File.createTempFile("shot_", ".jpg", ctx.cacheDir)
        assets.open("1600x1200.jpg").use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        sources += dst
        return dst
    }

    private fun brokenSourceFile(): File {
        val dst = File.createTempFile("broken_", ".jpg", ctx.cacheDir)
        dst.writeText("это не jpeg")
        sources += dst
        return dst
    }

    private fun intent(src: File, kind: String = "cargo") =
        PhotoIntent(kind = kind, stage = "before", sourcePath = src.absolutePath, takenAt = shotAt)

    private suspend fun finalize(id: String?, photos: List<PhotoIntent>): String =
        deliveries.upsert(
            DeliveryRepository.UpsertInput(
                id = id,
                statusCode = "confirmed_mol",
                siteId = siteId,
                arrivedAt = shotAt,
                confirmedByMolAt = shotAt,
                photos = photos,
            ),
        )

    /**
     * Главный инвариант: приёмка и её фото становятся durable вместе.
     * Раньше строки фото создавались после постановки мутации, и падение
     * подготовки означало приёмку без единого кадра.
     */
    @Test
    fun deliveryAndPhotosBecomeDurableInOneTransaction() = runBlocking {
        val id = finalize(null, listOf(intent(sourceFile()), intent(sourceFile(), kind = "document")))

        assertNotNull("приёмка не создана", db.remoteDeliveryDao().findById(id))
        assertEquals("мутация не поставлена", 1, db.mutationDao().findFor("delivery", id).size)
        val photos = db.remoteDeliveryDao().findPhotosByDelivery(id)
        assertEquals("фото не легли вместе с приёмкой", 2, photos.size)
        assertTrue(photos.all { it.uploadStatus == RemotePhotoStatus.PENDING_PREPARE })
        // Ключевое: тяжёлая работа ещё не делалась, blob'ов нет — но кадры
        // уже durable и переживут перезапуск процесса.
        assertTrue(photos.all { it.localBlobPath == null && it.sourcePath != null })
    }

    /**
     * Нет места → кадр остаётся в очереди и повторяется, а не пропадает.
     * Приёмка при этом уходит на сервер как обычно.
     */
    @Test
    fun noFreeSpaceKeepsPhotoInQueueAndRetriesLater() = runBlocking {
        val id = finalize(null, listOf(intent(sourceFile())))

        freeSpace = 1_000L
        prepare.processAll()

        val afterFailure = db.remoteDeliveryDao().findPhotosByDelivery(id).single()
        assertEquals(RemotePhotoStatus.PREPARE_ERROR, afterFailure.uploadStatus)
        assertNotNull("причина не сохранена", afterFailure.lastUploadError)
        assertNull("lease должен быть снят", afterFailure.preparingSince)

        // Место освободилось — повтор доводит кадр до готовности к заливке.
        freeSpace = Long.MAX_VALUE
        prepare.processAll()

        val afterRetry = db.remoteDeliveryDao().findPhotosByDelivery(id).single()
        assertEquals(RemotePhotoStatus.PENDING_UPLOAD, afterRetry.uploadStatus)
        assertNotNull(afterRetry.localBlobPath)
        assertNotNull(afterRetry.contentHash)
    }

    /** Один битый кадр не должен блокировать соседние. */
    @Test
    fun brokenSourceDoesNotBlockOtherPhotos() = runBlocking {
        val good = sourceFile()
        val broken = brokenSourceFile()
        val id = finalize(null, listOf(intent(good), intent(broken)))

        prepare.processAll()

        val photos = db.remoteDeliveryDao().findPhotosByDelivery(id).associateBy { it.sourcePath }
        assertEquals(
            RemotePhotoStatus.PENDING_UPLOAD,
            photos[good.absolutePath]!!.uploadStatus,
        )
        assertEquals(
            RemotePhotoStatus.PREPARE_ERROR,
            photos[broken.absolutePath]!!.uploadStatus,
        )
    }

    /**
     * Повторная финализация (процесс умер до удаления черновика, инспектор
     * нажал «Завершить» ещё раз) не заводит второй комплект фото и не
     * откатывает уже подготовленную строку в PENDING_PREPARE.
     */
    @Test
    fun repeatedFinalizeDoesNotDuplicateOrResetPhotos() = runBlocking {
        val src = sourceFile()
        val id = finalize(null, listOf(intent(src)))
        prepare.processAll()

        val prepared = db.remoteDeliveryDao().findPhotosByDelivery(id).single()
        assertEquals(RemotePhotoStatus.PENDING_UPLOAD, prepared.uploadStatus)

        // Повтор с тем же id приёмки и тем же исходником.
        finalize(id, listOf(intent(src)))

        val photos = db.remoteDeliveryDao().findPhotosByDelivery(id)
        assertEquals("появился дубль фото", 1, photos.size)
        assertEquals(
            "подготовленная строка откатилась",
            RemotePhotoStatus.PENDING_UPLOAD,
            photos.single().uploadStatus,
        )
        assertEquals(prepared.localBlobPath, photos.single().localBlobPath)
    }

    /**
     * Исходник удаляется сразу после успешной подготовки: за долгий офлайн
     * полноразмерные кадры и их сжатые копии рядом переполняли планшет.
     */
    @Test
    fun sourceIsDeletedAfterSuccessfulPrepare() = runBlocking {
        val src = sourceFile()
        finalize(null, listOf(intent(src)))

        prepare.processAll()

        assertFalse("исходник должен быть удалён", src.exists())
    }

    /**
     * Смерть процесса в PREPARING не морозит кадр навсегда: просроченный
     * lease перехватывается следующим запуском.
     */
    @Test
    fun expiredPreparingLeaseIsReclaimed() = runBlocking {
        val id = finalize(null, listOf(intent(sourceFile())))
        val photo = db.remoteDeliveryDao().findPhotosByDelivery(id).single()
        // Имитируем убитый процесс: строка «в работе» со старым lease.
        db.remoteDeliveryDao().upsertPhoto(
            photo.copy(
                uploadStatus = RemotePhotoStatus.PREPARING,
                preparingSince = System.currentTimeMillis() - 2 * 60 * 60 * 1000L,
            ),
        )

        prepare.processAll()

        assertEquals(
            RemotePhotoStatus.PENDING_UPLOAD,
            db.remoteDeliveryDao().findPhotosByDelivery(id).single().uploadStatus,
        )
    }

    @Suppress("unused")
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
}
