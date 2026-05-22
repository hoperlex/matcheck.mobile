package com.example.matcheckmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.matcheckmobile.data.local.entity.Stage2DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface Stage2DraftDao {

    @Query("SELECT * FROM stage2_drafts WHERE deliveryId = :id")
    suspend fun findById(id: String): Stage2DraftEntity?

    @Query("SELECT * FROM stage2_drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Stage2DraftEntity>>

    @Query("SELECT deliveryId FROM stage2_drafts")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: Stage2DraftEntity)

    @Query("DELETE FROM stage2_drafts WHERE deliveryId = :id")
    suspend fun deleteById(id: String)
}
