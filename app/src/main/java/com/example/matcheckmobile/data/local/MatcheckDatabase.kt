package com.example.matcheckmobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.matcheckmobile.data.local.dao.CounterpartyDao
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
import com.example.matcheckmobile.data.local.dao.SiteDao
import com.example.matcheckmobile.data.local.dao.SourceDocumentDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.dao.UserDao
import com.example.matcheckmobile.data.local.entity.CounterpartyEntity
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
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentEntity
import com.example.matcheckmobile.data.local.entity.SourceDocumentItemEntity
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
        RemoteSourceDocumentEntity::class,
        RemoteSourceDocumentItemEntity::class,
        RemoteSourceDocumentAttachmentEntity::class,
        MutationEntity::class,
    ],
    version = 7,
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
    abstract fun remoteSourceDocumentDao(): RemoteSourceDocumentDao
    abstract fun mutationDao(): MutationDao

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
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}
