package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.QuarantinedOperationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertItem
import com.example.matcheckmobile.data.remote.api.dto.DeliveryUpsertRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Карантин операций группового протокола: работа инспектора не теряется ни при
 * каком исходе — снимок и копии фото пишутся ДО удаления оригиналов, сбой
 * записи оставляет всё как было.
 */
class GroupQuarantineTest {

    private lateinit var root: File
    private lateinit var port: FakePort
    private val savedDrafts = mutableListOf<Stage1DraftState>()
    private var ids = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gq").toFile()
        port = FakePort()
        savedDrafts.clear()
        ids = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun quarantine() = GroupQuarantine(
        port = port,
        filesDir = root,
        saveDraft = { savedDrafts += it },
        now = { 1_000L },
        newId = { "id-${++ids}" },
    )

    private fun file(rel: String, text: String): File =
        File(root, rel).apply { parentFile!!.mkdirs(); writeText(text) }

    private val payload = DeliveryUpsertRequest(
        id = "d-1",
        statusCode = "confirmed_mol",
        siteId = "s-1",
        vehiclePlate = "А123ВС77",
        comment = "1 Этап: \"разгрузка у ворот\"\n2 Этап: \"всё сошлось\"\nПримечание: УПД 15",
        inTransit = true,
        sourceDocumentIds = listOf("u-1", "u-2"),
        items = listOf(
            DeliveryUpsertItem(
                nameRaw = "Арматура", qtyActual = "11", unit = "т", lineNo = 1,
                price = "100", sourceDocumentId = "u-1", sourceDocumentItemId = "i-1",
            ),
            DeliveryUpsertItem(nameRaw = "Своя строка", qtyActual = "2", unit = "шт", lineNo = 2),
        ),
        groupId = "g-1",
        groupRevision = 5,
    )

    private fun mutation() = MutationEntity(
        id = "m-1",
        entityType = "delivery",
        operation = "upsert",
        entityId = "d-1",
        baseVersion = 0,
        payloadJson = GroupQuarantine.JSON.encodeToString(DeliveryUpsertRequest.serializer(), payload),
        attempts = 0,
        nextAttemptAt = null,
        lastError = null,
        conflictPending = false,
        createdAt = 1L,
    )

    private fun photo(
        id: String,
        kind: String,
        stage: String,
        blob: String? = null,
        thumb: String? = null,
        source: String? = null,
    ) = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = "d-1", kind = kind, stage = stage, s3Key = null, thumbS3Key = null,
        contentHash = null, takenAt = "2026-11-20T09:00:00Z", uploadedAt = null,
        idempotencyKey = id, contentType = "image/jpeg", localBlobPath = blob, localThumbPath = thumb,
        uploadStatus = "PENDING_UPLOAD", lastUploadError = null, sourcePath = source,
    )

    private fun aggregate(photos: List<RemoteDeliveryPhotoEntity>) = DeliveryAggregate(
        delivery = RemoteDeliveryEntity(
            id = "d-1", statusCode = "confirmed_mol", statusLabel = "", statusColor = null, siteId = "s-1",
            supplierId = null, contractorId = null, recipientMolId = null, vehiclePlate = "А123ВС77",
            driverName = null, arrivedAt = null, inspectorId = null, comment = null,
            confirmedByMolUserId = null, confirmedByMolUserEmail = null, confirmedByMolAt = null,
            pendingDeletionAt = null, pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
            pendingDeletionReason = null, version = 0, sourceDocumentIdsJson = "[]",
            createdAt = "", updatedAt = "",
        ),
        items = emptyList(),
        photos = photos,
        vehicleTypeCode = "truck_20t",
    )

    @Test
    fun `снимок и копии фото пишутся, оригиналы удаляются только после записи`() = runBlocking {
        val blob = file("remote_photos/p1.jpg", "blob-1")
        val thumb = file("remote_photos/p1.thumb.jpg", "thumb-1")
        // Кадр ещё не подготовлен: подготовленного файла нет, берём исходник.
        val source = file("operation_photos/op_2.jpg", "source-2")
        port.aggregate = aggregate(
            listOf(
                photo("p1", "document", "before", blob = blob.path, thumb = thumb.path),
                photo("p2", "cargo", "after", blob = File(root, "remote_photos/missing.jpg").path, source = source.path),
            ),
        )

        val out = quarantine().quarantineDelivery(
            mutation(),
            MutationFailure.GroupConflict("group_claimed", claimedAt = "2026-11-20T09:15:00Z"),
        )

        assertEquals(GroupQuarantine.Outcome.Quarantined(id = "id-1", photos = 2, lostPhotos = 0), out)
        val row = port.rows.single()
        assertEquals("group_claimed", row.reason)
        assertEquals("2026-11-20T09:15:00Z", row.claimedAt)
        assertEquals("g-1", row.groupId)
        assertEquals("confirmed_mol", row.statusCode)
        assertEquals(2, row.photoCount)
        // Копии принадлежат записи карантина.
        val dir = File(root, "group_quarantine/id-1")
        assertEquals("blob-1", File(dir, "p1.jpg").readText())
        assertEquals("source-2", File(dir, "p2.jpg").readText())
        // Оригиналы ушли — но уже после коммита.
        assertFalse(blob.exists())
        assertFalse(thumb.exists())
        assertFalse(source.exists())
        // Снимок полный: тело запроса как есть и тип машины.
        val snapshot = GroupQuarantine.JSON.decodeFromString(GroupQuarantineSnapshot.serializer(), row.snapshotJson)
        assertEquals(payload, snapshot.payload)
        assertEquals("truck_20t", snapshot.vehicleTypeCode)
        assertEquals(listOf("p1.jpg", "p2.jpg"), snapshot.photos.map { it.file })
    }

    @Test
    fun `сбой записи — копии убраны, оригиналы и очередь нетронуты`() = runBlocking {
        val blob = file("remote_photos/p1.jpg", "blob-1")
        port.aggregate = aggregate(listOf(photo("p1", "cargo", "before", blob = blob.path)))
        port.failCommit = true

        try {
            quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))
            fail("ожидали исключение")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }

        assertTrue(port.rows.isEmpty())
        assertTrue(blob.exists())
        assertFalse(File(root, "group_quarantine/id-1").exists())
    }

    @Test
    fun `файл кадра есть, но не скопировался — карантин не пишется, оригинал цел`() = runBlocking {
        val blob = file("remote_photos/p1.jpg", "blob-1")
        port.aggregate = aggregate(listOf(photo("p1", "cargo", "before", blob = blob.path)))
        // Место копии занято непустым каталогом: copyTo не может его заменить —
        // так же, как при нехватке места или ошибке чтения.
        file("group_quarantine/id-1/p1.jpg/occupied", "x")

        try {
            quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_claimed"))
            fail("ожидали RetryLaterException")
        } catch (e: GroupQuarantine.RetryLaterException) {
            // ожидаемо: раньше кадр молча считался потерянным, а оригинал удалялся
        }

        assertTrue(port.rows.isEmpty())
        assertEquals("blob-1", blob.readText())
        assertFalse(File(root, "group_quarantine/id-1").exists())
    }

    @Test
    fun `кадр снят или подготовлен после снимка — повтор позже, ничего не удалено`() = runBlocking {
        val blob = file("remote_photos/p1.jpg", "blob-1")
        val p1 = photo("p1", "cargo", "before", blob = blob.path)
        port.aggregate = aggregate(listOf(p1))
        // Пока копировали, инспектор снял кадр 2 Этапа — его копии в снимке нет.
        port.photosAtCommit = listOf(p1, photo("p2", "cargo", "after", source = file("operation_photos/p2.jpg", "p2").path))

        try {
            quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))
            fail("ожидали RetryLaterException")
        } catch (e: GroupQuarantine.RetryLaterException) {
            // ожидаемо
        }

        assertTrue(port.rows.isEmpty())
        assertTrue(blob.exists())
        assertTrue(File(root, "operation_photos/p2.jpg").exists())
        assertFalse(File(root, "group_quarantine/id-1").exists())
    }

    @Test
    fun `сверка снимка — по id и путям файлов, статус загрузки не важен`() {
        val p = photo("p1", "cargo", "before", blob = "/b/p1.jpg", source = "/s/p1.jpg")
        assertTrue(GroupQuarantine.samePhotoFiles(listOf(p.copy(uploadStatus = "UPLOADED")), listOf(p)))
        // Подготовка закончилась: JPEG появился, исходник удалён.
        assertFalse(GroupQuarantine.samePhotoFiles(listOf(p.copy(sourcePath = null)), listOf(p)))
        assertFalse(GroupQuarantine.samePhotoFiles(emptyList(), listOf(p)))
    }

    @Test
    fun `исходник, нужный другой строке, не удаляется`() = runBlocking {
        val source = file("operation_photos/op_shared.jpg", "shared")
        port.aggregate = aggregate(listOf(photo("p1", "cargo", "before", source = source.path)))
        port.stillUsed += source.path

        quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))

        assertTrue(source.exists())
        assertEquals("shared", File(root, "group_quarantine/id-1/p1.jpg").readText())
    }

    @Test
    fun `кадр без файлов на планшете честно помечен потерянным`() = runBlocking {
        port.aggregate = aggregate(listOf(photo("p1", "cargo", "before", blob = File(root, "nope.jpg").path)))

        val out = quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))

        assertEquals(GroupQuarantine.Outcome.Quarantined(id = "id-1", photos = 0, lostPhotos = 1), out)
    }

    @Test
    fun `локальной приёмки нет — снимок из тела запроса всё равно пишется`() = runBlocking {
        port.aggregate = null

        quarantine().quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))

        assertEquals(1, port.rows.size)
        assertEquals(0, port.rows.single().photoCount)
    }

    @Test
    fun `оформить заново — черновик 1 Этапа с копиями фото 1 Этапа, 2 Этап остаётся`() = runBlocking {
        port.aggregate = aggregate(
            listOf(
                photo("doc", "document", "before", blob = file("remote_photos/doc.jpg", "doc").path),
                photo("car", "cargo", "before", blob = file("remote_photos/car.jpg", "car").path),
                photo("after", "cargo", "after", blob = file("remote_photos/after.jpg", "after").path),
            ),
        )
        val gq = quarantine()
        gq.quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))

        val result = gq.reissue("id-1")

        assertTrue(result is GroupQuarantine.ReissueResult.Created)
        val draft = savedDrafts.single()
        assertEquals((result as GroupQuarantine.ReissueResult.Created).draftId, draft.localDraftId)
        assertEquals("u-1", draft.updId)
        assertEquals(listOf("u-1", "u-2"), draft.loadedDocIds)
        assertEquals("g-1", draft.groupId)
        assertEquals(5, draft.loadedGroupRevision)
        assertEquals("А123ВС77", draft.licensePlate)
        assertEquals("разгрузка у ворот", draft.commentText)
        assertEquals("УПД 15", draft.manualUpdText)
        assertEquals("truck_20t", draft.vehicleTypeCode)
        assertTrue(draft.inTransit)
        assertEquals(listOf("Арматура", "Своя строка"), draft.materials.map { it.name })
        assertEquals("u-1", draft.materials.first().sourceDocumentId)
        assertNull(draft.materials.last().sourceDocumentId)
        // Только 1 Этап, и это КОПИИ в operation_photos — запись карантина цела.
        assertEquals(listOf("doc"), draft.documentPhotoPaths.map { File(it).readText() })
        assertEquals(listOf("car"), draft.cargoPhotoPaths.map { File(it).readText() })
        assertTrue(draft.cargoPhotoPaths.single().contains("operation_photos"))
        assertTrue(File(root, "group_quarantine/id-1/after.jpg").exists())
        assertEquals(1_000L, port.rows.single().reissuedAt)

        assertEquals(GroupQuarantine.ReissueResult.AlreadyReissued, gq.reissue("id-1"))
    }

    @Test
    fun `машину оформил другой планшет — оформить заново нельзя`() = runBlocking {
        port.aggregate = aggregate(emptyList())
        val gq = quarantine()
        gq.quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_claimed"))

        assertEquals(GroupQuarantine.ReissueResult.NotAllowed, gq.reissue("id-1"))
        assertTrue(savedDrafts.isEmpty())
    }

    @Test
    fun `удалить — запись и её файлы`() = runBlocking {
        port.aggregate = aggregate(listOf(photo("p1", "cargo", "before", blob = file("remote_photos/p1.jpg", "x").path)))
        val gq = quarantine()
        gq.quarantineDelivery(mutation(), MutationFailure.GroupConflict("group_changed"))
        assertNotNull(port.find("id-1"))

        gq.delete("id-1")

        assertTrue(port.rows.isEmpty())
        assertFalse(File(root, "group_quarantine/id-1").exists())
    }

    private class FakePort : GroupQuarantinePort {
        var aggregate: DeliveryAggregate? = null
        var failCommit = false
        /** Фото приёмки в момент записи; null — как в снимке. */
        var photosAtCommit: List<RemoteDeliveryPhotoEntity>? = null
        val rows = mutableListOf<QuarantinedOperationEntity>()
        val stillUsed = mutableSetOf<String>()

        override fun observeAll(): Flow<List<QuarantinedOperationEntity>> = flowOf(rows.toList())
        override suspend fun deliveryAggregate(deliveryId: String) = aggregate
        override suspend fun commit(row: QuarantinedOperationEntity, expectedPhotos: List<RemoteDeliveryPhotoEntity>) {
            // Как RoomGroupQuarantinePort: сверка со снимком — до любых изменений.
            val now = photosAtCommit ?: aggregate?.photos.orEmpty()
            if (!GroupQuarantine.samePhotoFiles(now, expectedPhotos)) {
                throw GroupQuarantine.RetryLaterException("фото изменились")
            }
            if (failCommit) throw IllegalStateException("disk full")
            rows += row
        }
        override suspend fun find(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun markReissued(id: String, at: Long) {
            val i = rows.indexOfFirst { it.id == id }
            rows[i] = rows[i].copy(reissuedAt = at)
        }
        override suspend fun delete(id: String) {
            rows.removeAll { it.id == id }
        }
        override suspend fun sourceStillUsed(path: String) = path in stillUsed
    }
}
