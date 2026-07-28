package com.example.matcheckmobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.data.local.MatcheckDatabase
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
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

/**
 * Данные-гарантии server-win-разрешения терминальных конфликтов приёмок на
 * настоящем Room (in-memory, реальный withTransaction через RoomTransactionRunner):
 * - терминал → приёмка `confirmed_mol`, `conflictPending=false`;
 * - удаляется ТОЛЬКО конфликтная мутация, поздняя неконфликтная СОХРАНЯЕТСЯ;
 * - локальное `PENDING_UPLOAD` фото не пропадает;
 * - нетерминал → Skipped, ничего не тронуто;
 * - сбой телеметрии не ломает разрешение.
 */
@RunWith(AndroidJUnit4::class)
class TerminalConflictResolverTest {

    private lateinit var db: MatcheckDatabase
    private lateinit var deliveryDao: RemoteDeliveryDao
    private lateinit var mutationDao: MutationDao
    private lateinit var tx: TransactionRunner

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MatcheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deliveryDao = db.remoteDeliveryDao()
        mutationDao = db.mutationDao()
        tx = RoomTransactionRunner(db)
    }

    @After
    fun teardown() = db.close()

    // --- helpers ---

    private fun delivery(id: String, status: String, conflict: Boolean) = RemoteDeliveryEntity(
        id = id, statusCode = status, statusLabel = status, statusColor = null,
        siteId = "site-1", supplierId = null, contractorId = null, recipientMolId = null,
        vehiclePlate = null, driverName = null, arrivedAt = "2026-07-24T00:00:00Z",
        inspectorId = null, comment = null, confirmedByMolUserId = null,
        confirmedByMolUserEmail = null, confirmedByMolAt = null, pendingDeletionAt = null,
        pendingDeletionByUserId = null, pendingDeletionByUserEmail = null,
        pendingDeletionReason = null, version = if (conflict) 2 else 1,
        sourceDocumentIdsJson = "[]", createdAt = "2026-07-24T00:00:00Z",
        updatedAt = "2026-07-24T00:00:00Z", conflictPending = conflict,
        serverSnapshotJson = if (conflict) "{\"snapshot\":true}" else null,
        lastSyncError = if (conflict) "conflict serverVersion=2" else null,
    )

    private fun pendingPhoto(id: String, deliveryId: String) = RemoteDeliveryPhotoEntity(
        id = id, deliveryId = deliveryId, kind = "vehicle", stage = "before",
        s3Key = null, thumbS3Key = null, contentHash = "hash-$id", takenAt = "2026-07-24T00:00:00Z",
        uploadedAt = null, idempotencyKey = "idem-$id", contentType = "image/jpeg",
        localBlobPath = "/tmp/$id.jpg", localThumbPath = null,
        uploadStatus = "PENDING_UPLOAD", lastUploadError = null,
    )

    private fun mutation(id: String, deliveryId: String, conflict: Boolean, op: String = "upsert") =
        MutationEntity(
            id = id, entityType = "delivery", operation = op, entityId = deliveryId,
            baseVersion = 1, payloadJson = "{\"comment\":\"local edit\"}", attempts = 1,
            nextAttemptAt = null, lastError = if (conflict) "conflict" else null,
            conflictPending = conflict, createdAt = 1L,
        )

    private fun resolver(telemetry: (List<MutationEntity>) -> Unit = DiscardTelemetry.noop) =
        TerminalConflictResolver(deliveryDao, mutationDao, tx, telemetry)

    // --- tests ---

    @Test
    fun sweepLocalTerminal_clearsConflict_keepsPhoto_deletesConflictMutation() = runBlocking {
        val id = "d1"
        deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = true), emptyList(), listOf(pendingPhoto("p1", id)))
        mutationDao.upsert(mutation("m1", id, conflict = true))

        resolver().sweepLocalTerminal()

        val row = deliveryDao.findById(id)!!
        assertFalse("conflictPending снят", row.conflictPending)
        assertNull(row.serverSnapshotJson)
        assertNull(row.lastSyncError)
        assertTrue("конфликтная мутация удалена", mutationDao.findFor("delivery", id).isEmpty())
        assertNotNull("PENDING_UPLOAD фото сохранено", deliveryDao.findPhotoById("p1"))
    }

    @Test
    fun sweepLocalTerminal_nonTerminalLocal_untouched() = runBlocking {
        val id = "d2"
        deliveryDao.saveAggregate(delivery(id, "filled", conflict = true), emptyList(), emptyList())
        mutationDao.upsert(mutation("m2", id, conflict = true))

        resolver().sweepLocalTerminal()

        assertTrue("нетерминал — конфликт остаётся", deliveryDao.findById(id)!!.conflictPending)
        assertEquals(1, mutationDao.findFor("delivery", id).size)
    }

    @Test
    fun applyReconciled_terminal_serverWin_keepsLaterNonConflictMutation() = runBlocking {
        val id = "d3"
        deliveryDao.saveAggregate(delivery(id, "filled", conflict = true), emptyList(), listOf(pendingPhoto("p3", id)))
        mutationDao.upsert(mutation("m3-conflict", id, conflict = true))
        mutationDao.upsert(mutation("m3-later", id, conflict = false, op = "mark_deletion")) // поздняя неконфликтная

        val out = resolver().applyReconciled(id, isServerTerminal = true) {
            // серверный snapshot: confirmed_mol, фото не присылает (photos=[]) → локальное PENDING сохраняется
            deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = false), emptyList(), emptyList())
        }

        assertTrue(out is TerminalConflictResolver.Outcome.Applied)
        assertEquals(1, (out as TerminalConflictResolver.Outcome.Applied).discarded.size)
        val row = deliveryDao.findById(id)!!
        assertEquals("confirmed_mol", row.statusCode)
        assertFalse(row.conflictPending)
        val muts = mutationDao.findFor("delivery", id)
        assertEquals("осталась только поздняя неконфликтная мутация", 1, muts.size)
        assertEquals("m3-later", muts.first().id)
        assertNotNull("фото сохранено", deliveryDao.findPhotoById("p3"))
    }

    @Test
    fun applyReconciled_nonTerminal_skipped_nothingChanged() = runBlocking {
        val id = "d4"
        deliveryDao.saveAggregate(delivery(id, "filled", conflict = true), emptyList(), emptyList())
        mutationDao.upsert(mutation("m4", id, conflict = true))
        var wrote = false

        val out = resolver().applyReconciled(id, isServerTerminal = false) { wrote = true }

        assertTrue(out is TerminalConflictResolver.Outcome.Skipped)
        assertFalse("write() не вызывался", wrote)
        assertTrue(deliveryDao.findById(id)!!.conflictPending)
        assertEquals(1, mutationDao.findFor("delivery", id).size)
    }

    @Test
    fun telemetryThrows_resolutionStillCompletes() = runBlocking {
        val id = "d5"
        deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = true), emptyList(), emptyList())
        mutationDao.upsert(mutation("m5", id, conflict = true))

        // Телеметрия бросает — разрешение всё равно должно завершиться без исключения.
        resolver(telemetry = { error("telemetry boom") }).sweepLocalTerminal()

        assertFalse(deliveryDao.findById(id)!!.conflictPending)
        assertTrue(mutationDao.findFor("delivery", id).isEmpty())
    }

    @Test
    fun sweep_orphanViaListConflicts_cleaned_evenWhenEntityFlagCleared() = runBlocking {
        // Blocker 1(b): флаг entity уже снят (напр. поздней мутацией), но конфликтная
        // upsert-мутация висит сиротой. Union-скан по listConflicts должен её убрать.
        val id = "d6"
        deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = false), emptyList(), emptyList())
        mutationDao.upsert(mutation("m6", id, conflict = true)) // сирота: mutation conflict, entity flag=false

        resolver().sweepLocalTerminal()

        assertTrue("сирота-мутация удалена union-сканом", mutationDao.findFor("delivery", id).isEmpty())
    }

    @Test
    fun sweep_keepsNonUpsertConflict_andKeepsEntityFlag() = runBlocking {
        // Blocker 2: удаляем только конфликтный upsert; конфликтный mark_deletion
        // остаётся, флаг entity НЕ снимается (есть другой конфликт).
        val id = "d7"
        deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = true), emptyList(), emptyList())
        mutationDao.upsert(mutation("m7-upsert", id, conflict = true, op = "upsert"))
        mutationDao.upsert(mutation("m7-mark", id, conflict = true, op = "mark_deletion"))

        resolver().sweepLocalTerminal()

        val muts = mutationDao.findFor("delivery", id)
        assertEquals("остался только mark_deletion", 1, muts.size)
        assertEquals("m7-mark", muts.first().id)
        assertTrue("флаг entity сохранён — есть другой конфликт", deliveryDao.findById(id)!!.conflictPending)
    }

    @Test
    fun applyReconciled_keepsNonUpsertConflict_reflagsEntity() = runBlocking {
        val id = "d8"
        deliveryDao.saveAggregate(delivery(id, "filled", conflict = true), emptyList(), emptyList())
        mutationDao.upsert(mutation("m8-upsert", id, conflict = true, op = "upsert"))
        mutationDao.upsert(mutation("m8-mark", id, conflict = true, op = "mark_deletion"))

        val out = resolver().applyReconciled(id, isServerTerminal = true) {
            deliveryDao.saveAggregate(delivery(id, "confirmed_mol", conflict = false), emptyList(), emptyList())
        }

        assertTrue(out is TerminalConflictResolver.Outcome.Applied)
        val row = deliveryDao.findById(id)!!
        assertEquals("confirmed_mol", row.statusCode)
        assertTrue("флаг возвращён — остался mark_deletion конфликт", row.conflictPending)
        val muts = mutationDao.findFor("delivery", id)
        assertEquals(1, muts.size)
        assertEquals("m8-mark", muts.first().id)
    }
}
