package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.ManualDispatchDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualDispatchDraftDao {

    @Query("SELECT * FROM manual_dispatch_drafts WHERE localDraftId = :id")
    suspend fun findById(id: String): ManualDispatchDraftEntity?

    @Query("SELECT * FROM manual_dispatch_drafts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ManualDispatchDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ManualDispatchDraftEntity)

    @Query("DELETE FROM manual_dispatch_drafts WHERE localDraftId = :id")
    suspend fun deleteById(id: String)

    /** См. ManualEntryDraftDao.count. */
    @Query("SELECT COUNT(*) FROM manual_dispatch_drafts")
    suspend fun count(): Int
}
