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
 * Миграции 24 → 25, 25 → 26 и 26 → 27 не должны терять локальные данные инспектора.
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
     *
     * Только для схемы 26: с 27 индекс неуникальный (см.
     * [migrate26To27_secondDraftOfSameMachineAndQuarantineRow]).
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

    /**
     * Миграция 26 → 27 (выпуск 2, «одна машина — одна приёмка») — путь всех
     * боевых планшетов 1.0.40. Отката у планшета нет: меньший versionCode
     * поверх не ставится, а старая сборка базу версии 27 не откроет. Поэтому
     * очередь, черновики и фото обязаны пережить обновление целиком.
     */
    @Test
    fun migrate26To27_keepsQueueDraftsAndPhotos() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            db.execSQL(
                """
                INSERT INTO mutations
                    (id, entityType, entityId, operation, payloadJson, baseVersion,
                     attempts, nextAttemptAt, lastError, conflictPending, createdAt)
                VALUES
                    ('mut-26', 'delivery', '$DELIVERY_ID', 'upsert', '$MUTATION_PAYLOAD',
                     0, 2, 0, 'http 503', 0, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO remote_deliveries
                    (id, statusCode, statusLabel, statusColor, siteId, supplierId, contractorId,
                     recipientMolId, vehiclePlate, driverName, arrivedAt, inspectorId, comment,
                     inTransit, isAssets, confirmedByMolAt, sourceDocumentIdsJson, version,
                     createdAt, updatedAt, conflictPending, pendingDeletionAt)
                VALUES
                    ('$DELIVERY_ID', 'filled', 'Заполнено', NULL, 'site-1', NULL, NULL,
                     NULL, 'С676ВК977', NULL, '2026-11-20T02:33:00Z', NULL, NULL,
                     0, 0, NULL, '["sd-1","sd-2"]', 0,
                     '2026-11-20T02:33:00Z', '2026-11-20T02:33:00Z', 0, NULL)
                """.trimIndent(),
            )
            // Кадр снят, но ещё не подготовлен: есть только исходник.
            db.execSQL(
                """
                INSERT INTO remote_delivery_photos
                    (id, deliveryId, kind, stage, s3Key, thumbS3Key, contentHash, takenAt,
                     uploadedAt, idempotencyKey, contentType, localBlobPath, localThumbPath,
                     uploadStatus, lastUploadError, sourcePath, preparingSince)
                VALUES
                    ('$PHOTO_ID', '$DELIVERY_ID', 'cargo', 'before', NULL, NULL, NULL,
                     '2026-11-20T02:33:10Z', NULL, 'idem-26', 'image/jpeg', NULL, NULL,
                     '${RemotePhotoStatus.PENDING_PREPARE}', NULL, '/data/operation_photos/op_26.jpg', NULL)
                """.trimIndent(),
            )
            // Черновик машины из двух документов: фото, правка количества, госномер.
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, groupId, loadedDocIdsJson, loadedGroupRevision,
                     createdAt, updatedAt)
                VALUES ('draft-g', 'sd-1', '["/doc.jpg"]', '["/cargo.jpg"]', 'truck',
                        '$DRAFT_MATERIALS', 'без замечаний', 'А111АА77', '', 1, 0,
                        'bundle-1', '["sd-1","sd-2"]', 3, 100, 200)
                """.trimIndent(),
            )
            // Пустая приёмка без документа («Создать приёмку»).
            db.execSQL(
                """
                INSERT INTO stage1_drafts
                    (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                     vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                     inTransit, isAssets, groupId, loadedDocIdsJson, loadedGroupRevision,
                     createdAt, updatedAt)
                VALUES ('draft-manual', NULL, '[]', '["/m.jpg"]', NULL, '[]', '', '', '', 0, 0,
                        NULL, '[]', NULL, 1, 1)
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
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 27, true, *MIGRATIONS)

        // Очередь: мутация с телом и счётчиком попыток как была.
        db.query(
            "SELECT payloadJson, attempts, lastError, conflictPending FROM mutations WHERE id = 'mut-26'",
        ).use { c ->
            assertTrue("мутация потеряна", c.moveToFirst())
            assertEquals(MUTATION_PAYLOAD, c.getString(0))
            assertEquals(2, c.getInt(1))
            assertEquals("http 503", c.getString(2))
            assertEquals(0, c.getInt(3))
        }
        assertEquals(1, db.count("SELECT COUNT(*) FROM remote_deliveries WHERE id = '$DELIVERY_ID'"))

        // Неподготовленный кадр по-прежнему ждёт подготовки со своим исходником.
        db.query(
            "SELECT uploadStatus, sourcePath FROM remote_delivery_photos WHERE id = '$PHOTO_ID'",
        ).use { c ->
            assertTrue("строка фото исчезла", c.moveToFirst())
            assertEquals(RemotePhotoStatus.PENDING_PREPARE, c.getString(0))
            assertEquals("/data/operation_photos/op_26.jpg", c.getString(1))
        }

        // Черновики целы до последнего поля, новые колонки — '[]'.
        assertEquals(2, db.count("SELECT COUNT(*) FROM stage1_drafts"))
        db.query(
            """
            SELECT documentPhotoPathsJson, cargoPhotoPathsJson, vehicleTypeCode, materialsJson,
                   commentText, licensePlate, inTransit, groupId, loadedDocIdsJson,
                   loadedGroupRevision, createdAt, mergedVariantsJson, parkedMaterialsJson
            FROM stage1_drafts WHERE localDraftId = 'draft-g'
            """.trimIndent(),
        ).use { c ->
            assertTrue("черновик машины потерян", c.moveToFirst())
            assertEquals("[\"/doc.jpg\"]", c.getString(0))
            assertEquals("[\"/cargo.jpg\"]", c.getString(1))
            assertEquals("truck", c.getString(2))
            assertEquals(DRAFT_MATERIALS, c.getString(3))
            assertEquals("без замечаний", c.getString(4))
            assertEquals("А111АА77", c.getString(5))
            assertEquals(1, c.getInt(6))
            assertEquals("bundle-1", c.getString(7))
            assertEquals("[\"sd-1\",\"sd-2\"]", c.getString(8))
            assertEquals(3, c.getInt(9))
            assertEquals(100L, c.getLong(10))
            assertEquals("[]", c.getString(11))
            assertEquals("[]", c.getString(12))
        }
        db.query(
            "SELECT mergedVariantsJson, parkedMaterialsJson FROM stage1_drafts WHERE localDraftId = 'draft-manual'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[]", c.getString(0))
            assertEquals("[]", c.getString(1))
        }
        assertEquals(1, db.count("SELECT COUNT(*) FROM stage2_drafts"))

        // Карантин создан пустым: до первого отказа сервера в нём нечего держать.
        assertEquals(0, db.count("SELECT COUNT(*) FROM quarantined_operations"))
    }

    /**
     * С 27 индексы updId/groupId неуникальные. У одной машины законно бывает
     * два черновика (машины объединили на портале, а инспектор начинал обе
     * карточки), и запись второго не должна стирать первый: раньше REPLACE по
     * уникальному ключу молча удалял чужую строку вместе с фото и правками.
     */
    @Test
    fun migrate26To27_secondDraftOfSameMachineAndQuarantineRow() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            db.execSQL(stage1Draft("d1", updId = "sd-1", groupId = "bundle-1", extraColumns = false))
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 27, true, *MIGRATIONS)

        db.execSQL(stage1Draft("d2", updId = "sd-1", groupId = "bundle-1", extraColumns = true))
        assertEquals(2, db.count("SELECT COUNT(*) FROM stage1_drafts WHERE groupId = 'bundle-1'"))

        // REPLACE по первичному ключу меняет только свою строку — как пишет DAO.
        db.execSQL(
            stage1Draft("d2", updId = "sd-1", groupId = "bundle-1", extraColumns = true)
                .replaceFirst("INSERT INTO", "INSERT OR REPLACE INTO"),
        )
        assertEquals(1, db.count("SELECT COUNT(*) FROM stage1_drafts WHERE localDraftId = 'd1'"))
        assertEquals(2, db.count("SELECT COUNT(*) FROM stage1_drafts"))

        // Карантин принимает полную запись.
        db.execSQL(
            """
            INSERT INTO quarantined_operations
                (id, entityType, entityId, reason, claimedAt, siteId, groupId,
                 sourceDocumentIdsJson, vehiclePlate, statusCode, photoCount, snapshotJson,
                 reissuedAt, createdAt)
            VALUES
                ('q-1', 'delivery', '$DELIVERY_ID', 'group_claimed', '2026-11-20T10:00:00Z',
                 'site-1', 'bundle-1', '["sd-1"]', 'А111АА77', 'filled', 2, '{}', NULL, 1000)
            """.trimIndent(),
        )
        assertEquals(1, db.count("SELECT COUNT(*) FROM quarantined_operations WHERE id = 'q-1'"))
    }

    /**
     * 23 → 27 одним обновлением — путь отставших сборок. Room прогонит все
     * миграции подряд, и падение любой стёрло бы базу (fallbackToDestructiveMigration).
     */
    @Test
    fun migrate23To27_keepsMutationsAndDraftsAcrossAllSteps() {
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
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 27, true, *MIGRATIONS)

        assertEquals(1, db.count("SELECT COUNT(*) FROM mutations WHERE id = 'mut-23'"))
        db.query(
            "SELECT cargoPhotoPathsJson, groupId, mergedVariantsJson, parkedMaterialsJson " +
                "FROM stage1_drafts WHERE localDraftId = 'draft-23'",
        ).use { c ->
            assertTrue("черновик потерян", c.moveToFirst())
            assertEquals("[\"/z.jpg\"]", c.getString(0))
            assertNull(c.getString(1))
            assertEquals("[]", c.getString(2))
            assertEquals("[]", c.getString(3))
        }
    }

    /** Строка stage1_drafts; [extraColumns] — с колонками версии 27. */
    private fun stage1Draft(id: String, updId: String, groupId: String, extraColumns: Boolean): String {
        val cols = if (extraColumns) ", mergedVariantsJson, parkedMaterialsJson" else ""
        val vals = if (extraColumns) ", '[]', '[]'" else ""
        return """
            INSERT INTO stage1_drafts
                (localDraftId, updId, documentPhotoPathsJson, cargoPhotoPathsJson,
                 vehicleTypeCode, materialsJson, commentText, licensePlate, manualUpdText,
                 inTransit, isAssets, groupId, loadedDocIdsJson, loadedGroupRevision,
                 createdAt, updatedAt$cols)
            VALUES ('$id', '$updId', '[]', '["/$id.jpg"]', NULL, '[]', '', '', '', 0, 0,
                    '$groupId', '["$updId"]', 1, 1, 1$vals)
        """.trimIndent()
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

        /** Тело upsert 1 Этапа из очереди 1.0.40: без groupId — так и должно доехать. */
        const val MUTATION_PAYLOAD =
            "{\"statusCode\":\"filled\",\"siteId\":\"site-1\",\"sourceDocumentIds\":[\"sd-1\",\"sd-2\"]}"

        /** Две строки разных документов машины, у первой — правка количества. */
        const val DRAFT_MATERIALS =
            "[{\"name\":\"Арматура\",\"qty\":\"10\",\"unit\":\"шт\"," +
                "\"sourceDocumentId\":\"sd-1\",\"sourceDocumentItemId\":\"it-1\"}," +
                "{\"name\":\"Цемент\",\"qty\":\"3\",\"unit\":\"т\"," +
                "\"sourceDocumentId\":\"sd-2\",\"sourceDocumentItemId\":\"it-2\"}]"

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
            MatcheckDatabase.MIGRATION_26_27,
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
