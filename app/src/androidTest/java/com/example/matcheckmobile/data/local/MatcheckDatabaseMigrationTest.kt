package com.example.matcheckmobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matcheckmobile.domain.model.RemotePhotoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграции 24 → 25 и 25 → 26 не должны терять локальные данные инспектора.
 *
 * Зачем отдельный тест: `MatcheckDatabase.get` собран с
 * `fallbackToDestructiveMigration(dropAllTables = true)`. Любая ошибка в
 * миграции не падает заметно — она молча стирает БД, а вместе с ней
 * неотправленные мутации и черновики форм. То есть ровно те данные, ради
 * сохранности которых вводится PENDING_PREPARE.
 *
 * Проверяем: мутации, все шесть видов черновиков, существующие PENDING_UPLOAD
 * (у них есть localBlobPath и они обязаны идти прежним путём), и то, что новые
 * колонки sourcePath/preparingSince появились и принимают PENDING_PREPARE.
 */
@RunWith(AndroidJUnit4::class)
class MatcheckDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MatcheckDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate24To25_keepsMutationsDraftsAndPendingUploads() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            // Мутация в очереди отправки — самое дорогое, что есть локально.
            db.execSQL(
                """
                INSERT INTO mutations
                    (id, entityType, entityId, operation, payloadJson, baseVersion,
                     attempts, nextAttemptAt, lastError, conflictPending, createdAt)
                VALUES
                    ('mut-1', 'delivery', '$DELIVERY_ID', 'upsert', '{"statusCode":"confirmed_mol"}',
                     0, 0, 0, NULL, 0, 1000)
                """.trimIndent(),
            )

            // Приёмка + фото в PENDING_UPLOAD: blob уже собран, путь известен.
            db.execSQL(
                """
                INSERT INTO remote_deliveries
                    (id, statusCode, statusLabel, statusColor, siteId, supplierId, contractorId,
                     recipientMolId, vehiclePlate, driverName, arrivedAt, inspectorId, comment,
                     inTransit, isAssets, confirmedByMolAt, sourceDocumentIdsJson, version,
                     createdAt, updatedAt, conflictPending, pendingDeletionAt)
                VALUES
                    ('$DELIVERY_ID', 'confirmed_mol', 'Подтверждено МОЛ', NULL, 'site-1', NULL, NULL,
                     NULL, 'С676ВК977', NULL, '2026-08-12T02:33:00Z', NULL, NULL,
                     0, 0, '2026-08-12T02:35:00Z', '[]', 1,
                     '2026-08-12T02:33:00Z', '2026-08-12T02:35:00Z', 0, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO remote_delivery_photos
                    (id, deliveryId, kind, stage, s3Key, thumbS3Key, contentHash, takenAt,
                     uploadedAt, idempotencyKey, contentType, localBlobPath, localThumbPath,
                     uploadStatus, lastUploadError)
                VALUES
                    ('$PHOTO_ID', '$DELIVERY_ID', 'cargo', 'before', NULL, NULL, 'hash-1',
                     '2026-08-12T02:33:10Z', NULL, 'idem-1', 'image/jpeg',
                     '/data/remote_photos/$PHOTO_ID.jpg', '/data/remote_photos/$PHOTO_ID.thumb.jpg',
                     '${RemotePhotoStatus.PENDING_UPLOAD}', NULL)
                """.trimIndent(),
            )

            // По одной строке в каждый из шести черновиков.
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, createdAt, updatedAt)
                VALUES ('draft-s1', NULL, '[]', '["/a.jpg"]', NULL, '[]', '', 'С676ВК977', '', 0, 0, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO stage2_drafts
                    (deliveryId, documentPhotoPathsJson, vehiclePhotoPathsJson, vehicleTypeCode,
                     materialsJson, editedIndexesJson, commentText, createdAt, updatedAt)
                VALUES ('$DELIVERY_ID', '[]', '["/b.jpg"]', NULL, '[]', '[]', '', 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO shipment_stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, createdAt, updatedAt)
                VALUES ('draft-sh1', NULL, '[]', '["/c.jpg"]', NULL, '[]', '', 'В146ОЕ30', '', 0, 0, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO shipment_stage2_drafts
                    (shipmentId, documentPhotoPathsJson, vehiclePhotoPathsJson, vehicleTypeCode,
                     materialsJson, editedIndexesJson, commentText, createdAt, updatedAt)
                VALUES ('ship-1', '[]', '["/d.jpg"]', NULL, '[]', '[]', '', 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO manual_entry_drafts
                    (localDraftId, siteId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     materialsJson, commentText, manualUpdText, isAssets, createdAt, updatedAt)
                VALUES ('draft-me', 'site-1', '[]', '["/e.jpg"]', '[]', '', '', 0, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO manual_dispatch_drafts
                    (localDraftId, siteId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     materialsJson, commentText, manualUpdText, isAssets, createdAt, updatedAt)
                VALUES ('draft-md', 'site-1', '[]', '["/f.jpg"]', '[]', '', '', 0, 1, 1)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, *MIGRATIONS)

        assertEquals(1, db.count("SELECT COUNT(*) FROM mutations WHERE id = 'mut-1'"))

        // Черновики целы — все шесть.
        for (table in DRAFT_TABLES) {
            assertEquals("черновик $table потерян", 1, db.count("SELECT COUNT(*) FROM $table"))
        }

        // Существующее PENDING_UPLOAD не тронуто: статус и blob на месте,
        // новые колонки — NULL (кадр уже подготовлен, готовить нечего).
        db.query(
            "SELECT uploadStatus, localBlobPath, sourcePath, preparingSince " +
                "FROM remote_delivery_photos WHERE id = '$PHOTO_ID'",
        ).use { c ->
            assertTrue("строка фото исчезла", c.moveToFirst())
            assertEquals(RemotePhotoStatus.PENDING_UPLOAD, c.getString(0))
            assertEquals("/data/remote_photos/$PHOTO_ID.jpg", c.getString(1))
            assertNull(c.getString(2))
            assertTrue(c.isNull(3))
        }

        // Новая строка в PENDING_PREPARE: blob ещё нет, есть только исходник.
        db.execSQL(
            """
            INSERT INTO remote_delivery_photos
                (id, deliveryId, kind, stage, s3Key, thumbS3Key, contentHash, takenAt,
                 uploadedAt, idempotencyKey, contentType, localBlobPath, localThumbPath,
                 uploadStatus, lastUploadError, sourcePath, preparingSince)
            VALUES
                ('photo-new', '$DELIVERY_ID', 'cargo', 'before', NULL, NULL, NULL,
                 '2026-08-12T02:33:20Z', NULL, 'idem-2', 'image/jpeg', NULL, NULL,
                 '${RemotePhotoStatus.PENDING_PREPARE}', NULL, '/data/operation_photos/op_1.jpg', NULL)
            """.trimIndent(),
        )
        assertEquals(
            1,
            db.count(
                "SELECT COUNT(*) FROM remote_delivery_photos " +
                    "WHERE uploadStatus = '${RemotePhotoStatus.PENDING_PREPARE}'",
            ),
        )
    }

    /**
     * Уникальный индекс (deliveryId, sourcePath) — вторая линия защиты от
     * дублей при повторной финализации. NULL при этом конфликтовать не должен,
     * иначе серверные фото (у них sourcePath пуст) перестали бы вставляться.
     */
    @Test
    fun migrate24To25_uniqueIndexBlocksDuplicateSourcePathButAllowsNulls() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            db.execSQL(
                """
                INSERT INTO remote_deliveries
                    (id, statusCode, statusLabel, statusColor, siteId, supplierId, contractorId,
                     recipientMolId, vehiclePlate, driverName, arrivedAt, inspectorId, comment,
                     inTransit, isAssets, confirmedByMolAt, sourceDocumentIdsJson, version,
                     createdAt, updatedAt, conflictPending, pendingDeletionAt)
                VALUES
                    ('$DELIVERY_ID', 'filled', 'Оформлена', NULL, 'site-1', NULL, NULL,
                     NULL, NULL, NULL, '2026-08-12T02:33:00Z', NULL, NULL,
                     0, 0, NULL, '[]', 1, '2026-08-12T02:33:00Z', NULL, 0, NULL)
                """.trimIndent(),
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, *MIGRATIONS)

        fun insert(id: String, sourcePath: String?) {
            val value = sourcePath?.let { "'$it'" } ?: "NULL"
            db.execSQL(
                """
                INSERT INTO remote_delivery_photos
                    (id, deliveryId, kind, stage, s3Key, thumbS3Key, contentHash, takenAt,
                     uploadedAt, idempotencyKey, contentType, localBlobPath, localThumbPath,
                     uploadStatus, lastUploadError, sourcePath, preparingSince)
                VALUES
                    ('$id', '$DELIVERY_ID', 'cargo', 'before', NULL, NULL, NULL, '2026-08-12T02:33:10Z',
                     NULL, '$id-idem', 'image/jpeg', NULL, NULL,
                     '${RemotePhotoStatus.PENDING_PREPARE}', NULL, $value, NULL)
                """.trimIndent(),
            )
        }

        insert("p1", "/data/operation_photos/op_1.jpg")
        val duplicate = runCatching { insert("p2", "/data/operation_photos/op_1.jpg") }
        assertTrue("дубль sourcePath должен отбиваться индексом", duplicate.isFailure)

        // Два серверных фото без sourcePath — обе строки обязаны пройти.
        insert("p3", null)
        insert("p4", null)
        assertEquals(3, db.count("SELECT COUNT(*) FROM remote_delivery_photos"))
    }

    /**
     * Миграция 25 → 26 («машина»): старые черновики целы, groupId у них пуст —
     * форма подхватит их по updId и дозаполнит при первом сохранении. Форс
     * version = 0 обязателен: без него groupId у уже закэшированных УПД
     * остался бы NULL навсегда, и склейка машин не заработала бы вообще.
     */
    @Test
    fun migrate25To26_keepsDraftsAndForcesSourceDocReconcile() {
        helper.createDatabase(TEST_DB, 25).use { db ->
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, createdAt, updatedAt)
                VALUES ('draft-s1', 'upd-1', '[]', '["/a.jpg"]', NULL, '[]', '', 'С676ВК977', '', 0, 0, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO shipment_stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, createdAt, updatedAt)
                VALUES ('draft-sh1', NULL, '[]', '["/c.jpg"]', NULL, '[]', '', 'В146ОЕ30', '', 0, 0, 1, 1)
                """.trimIndent(),
            )
            // Свежий документ (внутри окна 90 дней) и древний — за окном.
            db.execSQL(sourceDoc("sd-fresh", "strftime('%Y-%m-%dT%H:%M:%SZ','now','-3 days')"))
            db.execSQL(sourceDoc("sd-old", "strftime('%Y-%m-%dT%H:%M:%SZ','now','-200 days')"))
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, *MIGRATIONS)

        // Черновики целы, groupId пуст — подхват по updId делает форма.
        assertEquals(1, db.count("SELECT COUNT(*) FROM stage1_drafts WHERE localDraftId = 'draft-s1'"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM shipment_stage1_drafts"))
        db.query("SELECT groupId, loadedDocIdsJson, loadedGroupRevision FROM stage1_drafts").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
            assertEquals("[]", c.getString(1))
            assertTrue(c.isNull(2))
        }

        // Форс-реконсиляция: свежий документ сброшен в version = 0, древний —
        // нет (сервер его назад не отдаст, он застрял бы навсегда).
        assertEquals(0, db.count("SELECT version FROM remote_source_documents WHERE id = 'sd-fresh'"))
        assertEquals(5, db.count("SELECT version FROM remote_source_documents WHERE id = 'sd-old'"))

        // Новые колонки на месте и принимают значения.
        db.execSQL(
            "UPDATE remote_source_documents SET groupId = 'bundle-1', groupRevision = 2 " +
                "WHERE id = 'sd-fresh'",
        )
        assertEquals(
            1,
            db.count("SELECT COUNT(*) FROM remote_source_documents WHERE groupId = 'bundle-1'"),
        )
    }

    /**
     * Сквозной путь ВСЕХ боевых планшетов: 23 → 26 одним обновлением.
     *
     * Отдельно от пошаговых тестов, потому что проверяет другое: те 23 → 24
     * никто не проходил по частям. В проде стоит схема 23, и при установке
     * релиза Room прогонит три миграции подряд — если хоть одна упадёт,
     * `fallbackToDestructiveMigration(dropAllTables = true)` молча снесёт и
     * неотправленные мутации, и черновики. Это единственная копия данных
     * инспектора: 1 Этап живёт локально до финализации.
     */
    @Test
    fun migrate23To26_keepsMutationsAndDraftsAcrossAllSteps() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            db.execSQL(
                """
                INSERT INTO mutations
                    (id, entityType, entityId, operation, payloadJson, baseVersion,
                     attempts, nextAttemptAt, lastError, conflictPending, createdAt)
                VALUES
                    ('mut-23', 'delivery', '$DELIVERY_ID', 'upsert', '{"statusCode":"filled"}',
                     0, 0, 0, NULL, 0, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, createdAt, updatedAt)
                VALUES ('draft-23', 'upd-23', '[]', '["/z.jpg"]', NULL, '[]', '', 'Т777ЕЕ77', '', 0, 0, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(sourceDoc("sd-23", "strftime('%Y-%m-%dT%H:%M:%SZ','now','-3 days')"))
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, *MIGRATIONS)

        assertEquals(1, db.count("SELECT COUNT(*) FROM mutations WHERE id = 'mut-23'"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM stage1_drafts WHERE localDraftId = 'draft-23'"))
        // Фото черновика на месте — ради них всё и затевалось.
        db.query("SELECT cargoPhotoPathsJson FROM stage1_drafts WHERE localDraftId = 'draft-23'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[\"/z.jpg\"]", c.getString(0))
        }
        // Обе колонки-новинки доехали: грузополучатель (24) и группа (26).
        db.execSQL(
            "UPDATE remote_source_documents SET consigneeName = 'ООО Ромашка', " +
                "groupId = 'bundle-23', groupRevision = 1 WHERE id = 'sd-23'",
        )
        assertEquals(
            1,
            db.count(
                "SELECT COUNT(*) FROM remote_source_documents " +
                    "WHERE consigneeName = 'ООО Ромашка' AND groupId = 'bundle-23'",
            ),
        )
        // Форс-реконсиляция сработала на обоих шагах, а не только на последнем.
        assertEquals(0, db.count("SELECT version FROM remote_source_documents WHERE id = 'sd-23'"))
    }

    /**
     * UNIQUE по groupId — одна машина, один черновик. NULL при этом
     * конфликтовать не должен: у приёмок без документа и у старых черновиков
     * groupId пуст, и их может быть сколько угодно.
     */
    @Test
    fun migrate25To26_uniqueGroupIdAllowsManyNulls() {
        helper.createDatabase(TEST_DB, 25).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, *MIGRATIONS)

        fun insert(id: String, groupId: String?) {
            val value = groupId?.let { "'$it'" } ?: "NULL"
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, groupId, loadedDocIdsJson, loadedGroupRevision,
                     createdAt, updatedAt)
                VALUES ('$id', NULL, '[]', '[]', NULL, '[]', '', '', '', 0, 0,
                        $value, '[]', NULL, 1, 1)
                """.trimIndent(),
            )
        }

        insert("d1", "bundle-1")
        val duplicate = runCatching { insert("d2", "bundle-1") }
        assertTrue("дубль groupId должен отбиваться индексом", duplicate.isFailure)

        insert("d3", null)
        insert("d4", null)
        assertEquals(3, db.count("SELECT COUNT(*) FROM stage1_drafts"))
    }

    /** Минимальная строка remote_source_documents для версии 25. */
    private fun sourceDoc(id: String, updatedAtSql: String): String = """
        INSERT INTO remote_source_documents
            (id, kind, direction, status, supplierId, recipientId, contractorId, recipientMolId,
             siteId, supplierName, contractorName, consigneeName, siteName, createdByUserPhone,
             docNumber, docDate, totalSum, vatSum, expectedDate, origin, parsedAt,
             parseErrorCode, originalFilename, version, createdAt, updatedAt)
        VALUES
            ('$id', 'upd', 'inbound', 'parsed', NULL, NULL, NULL, NULL,
             'site-1', NULL, NULL, NULL, NULL, NULL,
             '1403', NULL, NULL, NULL, NULL, 'manual_pdf', '2026-08-01T00:00:00Z',
             NULL, NULL, 5, '2026-08-01T00:00:00Z', $updatedAtSql)
    """.trimIndent()

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(sql: String): Int =
        query(sql).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val DELIVERY_ID = "11111111-1111-1111-1111-111111111111"
        const val PHOTO_ID = "22222222-2222-2222-2222-222222222222"

        /**
         * Миграции обязаны передаваться явно: helper создан с `emptyList()`
         * (автомиграций у нас нет), и без этого списка Room не найдёт пути и
         * упадёт с «A migration from N to M was required but not found» —
         * то есть тест не проверял бы вообще ничего.
         */
        val MIGRATIONS = arrayOf(
            MatcheckDatabase.MIGRATION_23_24,
            MatcheckDatabase.MIGRATION_24_25,
            MatcheckDatabase.MIGRATION_25_26,
        )
        val DRAFT_TABLES = listOf(
            "stage1_drafts",
            "stage2_drafts",
            "shipment_stage1_drafts",
            "shipment_stage2_drafts",
            "manual_entry_drafts",
            "manual_dispatch_drafts",
        )
    }
}
