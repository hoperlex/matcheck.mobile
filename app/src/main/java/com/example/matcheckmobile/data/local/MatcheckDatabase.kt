package com.example.matcheckmobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.matcheckmobile.data.local.dao.CounterpartyDao
import com.example.matcheckmobile.data.local.dao.DeliveryLocalMetaDao
import com.example.matcheckmobile.data.local.dao.ShipmentLocalMetaDao
import com.example.matcheckmobile.data.local.dao.ManualDispatchDraftDao
import com.example.matcheckmobile.data.local.dao.ManualEntryDraftDao
import com.example.matcheckmobile.data.local.dao.MaterialDao
import com.example.matcheckmobile.data.local.dao.MaterialOperationDao
import com.example.matcheckmobile.data.local.dao.MutationDao
import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.dao.ReceiptSessionDao
import com.example.matcheckmobile.data.local.dao.RemoteCounterpartyDao
import com.example.matcheckmobile.data.local.dao.RemoteDeliveryDao
import com.example.matcheckmobile.data.local.dao.RemoteMaterialDao
import com.example.matcheckmobile.data.local.dao.RemoteShipmentDao
import com.example.matcheckmobile.data.local.dao.RemoteSiteDao
import com.example.matcheckmobile.data.local.dao.RemoteSourceDocumentDao
import com.example.matcheckmobile.data.local.dao.RemoteStatusDao
import com.example.matcheckmobile.data.local.dao.RemoteUnitDao
import com.example.matcheckmobile.data.local.dao.SiteDao
import com.example.matcheckmobile.data.local.dao.SourceDocumentDao
import com.example.matcheckmobile.data.local.dao.ShipmentStage1DraftDao
import com.example.matcheckmobile.data.local.dao.ShipmentStage2DraftDao
import com.example.matcheckmobile.data.local.dao.Stage1DraftDao
import com.example.matcheckmobile.data.local.dao.Stage2DraftDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.dao.UserDao
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
import com.example.matcheckmobile.data.local.entity.DeliveryLocalMetaEntity
import com.example.matcheckmobile.data.local.entity.ShipmentLocalMetaEntity
import com.example.matcheckmobile.data.local.entity.ManualDispatchDraftEntity
import com.example.matcheckmobile.data.local.entity.ManualEntryDraftEntity
import com.example.matcheckmobile.data.local.entity.MaterialEntity
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.MutationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.ReceiptSessionEntity
import com.example.matcheckmobile.data.local.entity.RemoteCounterpartyEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteDeliveryPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteMaterialEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteShipmentPhotoEntity
import com.example.matcheckmobile.data.local.entity.RemoteSiteEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentAttachmentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.RemoteSourceDocumentItemEntity
import com.example.matcheckmobile.data.local.entity.RemoteStatusEntity
import com.example.matcheckmobile.data.local.entity.RemoteUnitEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
import com.example.matcheckmobile.data.local.entity.ShipmentStage1DraftEntity
import com.example.matcheckmobile.data.local.entity.ShipmentStage2DraftEntity
import com.example.matcheckmobile.data.local.entity.Stage1DraftEntity
import com.example.matcheckmobile.data.local.entity.Stage2DraftEntity
import com.example.matcheckmobile.data.local.entity.SyncQueueEntity
import com.example.matcheckmobile.data.local.entity.UserEntity

@Database(
    entities = [
        // Legacy (будут удалены в Этапе 6 после переключения UI на Remote*).
        UserEntity::class,
        SiteEntity::class,
        MaterialEntity::class,
        MaterialOperationEntity::class,
        OperationAttachmentEntity::class,
        SyncQueueEntity::class,
        CounterpartyEntity::class,
        SourceDocumentEntity::class,
        SourceDocumentItemEntity::class,
        ReceiptSessionEntity::class,
        DeliveryLocalMetaEntity::class,
        ShipmentLocalMetaEntity::class,
        // Серверная модель (источник правды — matcheck API).
        RemoteDeliveryEntity::class,
        RemoteDeliveryItemEntity::class,
        RemoteDeliveryPhotoEntity::class,
        RemoteShipmentEntity::class,
        RemoteShipmentItemEntity::class,
        RemoteShipmentPhotoEntity::class,
        RemoteCounterpartyEntity::class,
        RemoteMaterialEntity::class,
        RemoteSiteEntity::class,
        RemoteStatusEntity::class,
        RemoteUnitEntity::class,
        RemoteSourceDocumentEntity::class,
        RemoteSourceDocumentItemEntity::class,
        RemoteSourceDocumentAttachmentEntity::class,
        MutationEntity::class,
        Stage1DraftEntity::class,
        Stage2DraftEntity::class,
        ShipmentStage1DraftEntity::class,
        ShipmentStage2DraftEntity::class,
        ManualEntryDraftEntity::class,
        ManualDispatchDraftEntity::class,
    ],
    version = 23,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MatcheckDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun siteDao(): SiteDao
    abstract fun materialDao(): MaterialDao
    abstract fun materialOperationDao(): MaterialOperationDao
    abstract fun operationAttachmentDao(): OperationAttachmentDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun counterpartyDao(): CounterpartyDao
    abstract fun sourceDocumentDao(): SourceDocumentDao
    abstract fun receiptSessionDao(): ReceiptSessionDao

    abstract fun remoteDeliveryDao(): RemoteDeliveryDao
    abstract fun remoteShipmentDao(): RemoteShipmentDao
    abstract fun remoteCounterpartyDao(): RemoteCounterpartyDao
    abstract fun remoteMaterialDao(): RemoteMaterialDao
    abstract fun remoteSiteDao(): RemoteSiteDao
    abstract fun remoteStatusDao(): RemoteStatusDao
    abstract fun remoteUnitDao(): RemoteUnitDao
    abstract fun remoteSourceDocumentDao(): RemoteSourceDocumentDao
    abstract fun mutationDao(): MutationDao
    abstract fun deliveryLocalMetaDao(): DeliveryLocalMetaDao
    abstract fun shipmentLocalMetaDao(): ShipmentLocalMetaDao
    abstract fun stage1DraftDao(): Stage1DraftDao
    abstract fun stage2DraftDao(): Stage2DraftDao
    abstract fun shipmentStage1DraftDao(): ShipmentStage1DraftDao
    abstract fun shipmentStage2DraftDao(): ShipmentStage2DraftDao
    abstract fun manualEntryDraftDao(): ManualEntryDraftDao
    abstract fun manualDispatchDraftDao(): ManualDispatchDraftDao

    companion object {
        private const val DB_NAME = "matcheck.db"

        @Volatile
        private var INSTANCE: MatcheckDatabase? = null

        fun get(context: Context): MatcheckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MatcheckDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        // Добавлена таблица delivery_local_meta — локальный vehicleTypeCode,
        // который не приходит из server-snapshot и не должен теряться при /sync.
        // FK на remote_deliveries(id) с CASCADE.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `delivery_local_meta` (
                        `deliveryId` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        PRIMARY KEY(`deliveryId`),
                        FOREIGN KEY(`deliveryId`) REFERENCES `remote_deliveries`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        // manual_entry_drafts + manual_dispatch_drafts — локальные черновики
        // «Ручной внос/вынос». Самостоятельные сущности (не оверлей поверх
        // delivery/shipment): инспектор ведёт несколько незавершённых ручных
        // операций и возвращается к ним. На сервер уходят только по «Завершить»
        // (тогда строка черновика удаляется). Индексы по siteId (фильтр списка)
        // и updatedAt (как у stage1_drafts).
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `manual_entry_drafts` (
                        `localDraftId` TEXT NOT NULL,
                        `siteId` TEXT NOT NULL,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `cargoPhotoPathsJson` TEXT NOT NULL,
                        `manualUpdText` TEXT NOT NULL,
                        `materialsJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `isAssets` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localDraftId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_entry_drafts_siteId` ON `manual_entry_drafts` (`siteId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_entry_drafts_updatedAt` ON `manual_entry_drafts` (`updatedAt`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `manual_dispatch_drafts` (
                        `localDraftId` TEXT NOT NULL,
                        `siteId` TEXT NOT NULL,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `cargoPhotoPathsJson` TEXT NOT NULL,
                        `manualUpdText` TEXT NOT NULL,
                        `materialsJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `shipmentPurpose` TEXT,
                        `isAssets` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localDraftId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_dispatch_drafts_siteId` ON `manual_dispatch_drafts` (`siteId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_dispatch_drafts_updatedAt` ON `manual_dispatch_drafts` (`updatedAt`)",
                )
            }
        }

        // stage1_drafts — локальный черновик формы 1 Этапа: фото + поля.
        // Сохраняется автоматически Stage1FormViewModel'ью при наличии хотя бы
        // одного фото; удаляется после finalizeStage1 или когда все фото удалены.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stage1_drafts` (
                        `localDraftId` TEXT NOT NULL,
                        `updId` TEXT,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `cargoPhotoPathsJson` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        `materialsJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `licensePlate` TEXT NOT NULL,
                        `manualUpdText` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localDraftId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stage1_drafts_updId` ON `stage1_drafts` (`updId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stage1_drafts_updatedAt` ON `stage1_drafts` (`updatedAt`)",
                )
            }
        }

        // createdAt = момент «Начато» (первое фото). Для существующих drafts
        // (в проде их пока быть не должно — миграция свежая) backfill = updatedAt.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `stage1_drafts` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE `stage1_drafts` SET `createdAt` = `updatedAt` WHERE `createdAt` = 0",
                )
            }
        }

        // remote_source_documents.recipientMolId — Получатель-МОЛ из УПД.
        // На сервере у SourceDocument отдельные поля recipientId (исторически)
        // и recipientMolId (новое, физлицо МОЛ). Мобила раньше парсила только
        // recipientId — при создании приёмки маппинг шёл по ошибке и валил
        // CHECK deliveries_recipient_chk (contractor + recipientMol одновременно).
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `remote_source_documents` ADD COLUMN `recipientMolId` TEXT")
            }
        }

        // remote_deliveries.recipientMolId — Получатель из УПД (Подрядчик/МОЛ).
        // remote_delivery_items.price/vatRate/vatSum — финансовые поля из УПД,
        // нужны на веб-портале в карточке приёмки и в Материалах→Поступление.
        // Все поля nullable, существующие записи получат NULL — backfill приедет
        // при следующем /sync.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `remote_deliveries` ADD COLUMN `recipientMolId` TEXT")
                db.execSQL("ALTER TABLE `remote_delivery_items` ADD COLUMN `price` TEXT")
                db.execSQL("ALTER TABLE `remote_delivery_items` ADD COLUMN `vatRate` TEXT")
                db.execSQL("ALTER TABLE `remote_delivery_items` ADD COLUMN `vatSum` TEXT")
            }
        }

        // stage2_drafts — локальные правки формы 2 Этапа поверх серверного
        // снимка delivery. Один draft на deliveryId.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stage2_drafts` (
                        `deliveryId` TEXT NOT NULL,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `vehiclePhotoPathsJson` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        `materialsJson` TEXT NOT NULL,
                        `editedIndexesJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`deliveryId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stage2_drafts_updatedAt` ON `stage2_drafts` (`updatedAt`)",
                )
            }
        }

        // Догоняем веб-контракт 2026-05-27:
        // - remote_shipments.receiverMolId — МОЛ-получатель для отгрузки.
        // - remote_shipment_items.price/vatRate/vatSum — финансовый снимок
        //   позиции из УПД для отгрузки (зеркально delivery_items, миграция 0036).
        // - remote_delivery_photos.stage — этап фото 'before'/'after'
        //   (сервер: миграция 0037). Default 'before' для legacy фото.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `remote_shipments` ADD COLUMN `receiverMolId` TEXT")
                db.execSQL("ALTER TABLE `remote_shipment_items` ADD COLUMN `price` TEXT")
                db.execSQL("ALTER TABLE `remote_shipment_items` ADD COLUMN `vatRate` TEXT")
                db.execSQL("ALTER TABLE `remote_shipment_items` ADD COLUMN `vatSum` TEXT")
                db.execSQL(
                    "ALTER TABLE `remote_delivery_photos` ADD COLUMN `stage` TEXT NOT NULL DEFAULT 'before'",
                )
            }
        }

        // remote_source_documents.createdByUserPhone — телефон автора УПД
        // (того, кто загрузил её через веб /upload-upd*). Сервер: миграция 0039.
        // Нужен на 1 Этапе приёмки для иконки звонка в шапке модалки «Материалы».
        // Для EDO/mail-полученных УПД остаётся NULL — иконка не рисуется.
        // Существующие строки получат NULL, актуальное значение приедет при
        // следующем /sync.
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `remote_source_documents` ADD COLUMN `createdByUserPhone` TEXT")
            }
        }

        // shipment_local_meta — зеркало delivery_local_meta для отгрузки.
        // Хранит локальный vehicleTypeCode (Газель/Фура/…), который не
        // приходит в shipment DTO от сервера и терялся бы между 1 и 2 этапом.
        // FK на remote_shipments(id) с CASCADE: запись чистится при удалении.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shipment_local_meta` (
                        `shipmentId` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        PRIMARY KEY(`shipmentId`),
                        FOREIGN KEY(`shipmentId`) REFERENCES `remote_shipments`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        // ОС («основные средства») — флаг «накладная относится к движению
        // объектов ОС». Чекбокс на 1 этапе мобилы рядом с Транзитом.
        // Сервер: миграция 0065. Колонка добавляется в те же 4 таблицы,
        // что и inTransit (serverная пара + 2 draft'а). Дополнительно
        // создаётся справочник remote_units, который заполняется через
        // /sync (SyncRepository.applyResponse). Default 0/false для
        // существующих записей — ничего не ломается, legacy в порядке.
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `remote_deliveries` ADD COLUMN `isAssets` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `remote_shipments` ADD COLUMN `isAssets` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `stage1_drafts` ADD COLUMN `isAssets` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `shipment_stage1_drafts` ADD COLUMN `isAssets` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_units` (
                        `id` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `okeiCode` TEXT,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        // Транзит — флаг «приёмка/отгрузка является частью транзитного
        // рейса» (машина после нашей операции едет с другим грузом).
        // Чекбокс на 1 этапе мобилы. Сервер: миграция 0051.
        // Колонка добавляется во ВСЕ четыре таблицы: серверные снапшоты
        // (remote_deliveries, remote_shipments) и локальные черновики
        // (stage1_drafts, shipment_stage1_drafts). Default 0 (false) —
        // legacy записи получают false, ничего не теряется.
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `remote_deliveries` ADD COLUMN `inTransit` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `remote_shipments` ADD COLUMN `inTransit` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `stage1_drafts` ADD COLUMN `inTransit` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `shipment_stage1_drafts` ADD COLUMN `inTransit` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        // remote_shipments.purpose — серверное поле «Тип отгрузки» (см.
        // миграцию 0049 на web). 4 значения с мобилы: «Вывоз материала»,
        // «Перемещение на объект», «Вывоз мусора», «Другое». NULL для
        // отгрузок с привязанной УПД и для старых записей. Веб-портал
        // показывает чипом «Тип отгрузки: …» справа от «Водитель» в шапке
        // карточки отгрузки.
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `remote_shipments` ADD COLUMN `purpose` TEXT",
                )
            }
        }

        // shipment_stage1_drafts.shipmentPurpose — выбор пользователя в
        // dropdown «Тип отгрузки» на форме «Новая отгрузка» (empty-draft,
        // когда updId=null). 4 значения: «Вывоз материала / Перемещение на
        // объект / Вывоз мусора / Другое». Хранится локально, сервер видит
        // как префикс в comment'е («Тип: …»). NULL для старых draft'ов и
        // когда инспектор ничего не выбрал.
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `shipment_stage1_drafts` ADD COLUMN `shipmentPurpose` TEXT",
                )
            }
        }

        // remote_shipment_photos.stage — этап у фото отгрузки ('before'/'after').
        // Зеркало MIGRATION_13_14 для delivery_photos: на сервере та же
        // колонка введена миграцией 0048. Default 'before' — старые фото
        // относятся к 1-му Этапу (фичи «после» у отгрузки до этой миграции
        // не было, capture'ы шли без разметки).
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `remote_shipment_photos` ADD COLUMN `stage` TEXT NOT NULL DEFAULT 'before'",
                )
            }
        }

        // shipment_stage1_drafts + shipment_stage2_drafts — зеркала stage1/stage2_drafts
        // для отгрузки. Структура полей идентична.
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shipment_stage1_drafts` (
                        `localDraftId` TEXT NOT NULL,
                        `updId` TEXT,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `cargoPhotoPathsJson` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        `materialsJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `licensePlate` TEXT NOT NULL,
                        `manualUpdText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localDraftId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_shipment_stage1_drafts_updId` ON `shipment_stage1_drafts` (`updId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shipment_stage1_drafts_updatedAt` ON `shipment_stage1_drafts` (`updatedAt`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shipment_stage2_drafts` (
                        `shipmentId` TEXT NOT NULL,
                        `documentPhotoPathsJson` TEXT NOT NULL,
                        `vehiclePhotoPathsJson` TEXT NOT NULL,
                        `vehicleTypeCode` TEXT,
                        `materialsJson` TEXT NOT NULL,
                        `editedIndexesJson` TEXT NOT NULL,
                        `commentText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`shipmentId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shipment_stage2_drafts_updatedAt` ON `shipment_stage2_drafts` (`updatedAt`)",
                )
            }
        }
    }
}
