package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE targetType = :type AND targetLocalId = :id")
    suspend fun deleteByTarget(type: String, id: String)

    @Query("SELECT * FROM sync_queue ORDER BY createdAt")
    fun observeAll(): Flow<List<SyncQueueEntity>>

    /** Остатки legacy-очереди — для барьера при выходе. */
    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun count(): Int
}
