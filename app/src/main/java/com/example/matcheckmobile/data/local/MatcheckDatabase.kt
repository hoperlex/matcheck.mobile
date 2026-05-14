package com.example.matcheckmobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.matcheckmobile.data.local.dao.MaterialDao
import com.example.matcheckmobile.data.local.dao.MaterialOperationDao
import com.example.matcheckmobile.data.local.dao.OperationAttachmentDao
import com.example.matcheckmobile.data.local.dao.SiteDao
import com.example.matcheckmobile.data.local.dao.SyncQueueDao
import com.example.matcheckmobile.data.local.dao.UserDao
import com.example.matcheckmobile.data.local.entity.MaterialEntity
import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.local.entity.OperationAttachmentEntity
import com.example.matcheckmobile.data.local.entity.SiteEntity
import com.example.matcheckmobile.data.local.entity.SyncQueueEntity
import com.example.matcheckmobile.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        SiteEntity::class,
        MaterialEntity::class,
        MaterialOperationEntity::class,
        OperationAttachmentEntity::class,
        SyncQueueEntity::class,
    ],
    version = 1,
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
